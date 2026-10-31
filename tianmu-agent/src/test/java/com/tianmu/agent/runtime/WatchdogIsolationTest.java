package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.runtime.events.InMemoryRunEventStore;
import com.tianmu.agent.runtime.store.*;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WatchdogIsolationTest {
    RunState run(RunBackend backend, Duration remaining) {
        Instant now = Instant.now();
        var run = new RunState(UUID.randomUUID(), UUID.randomUUID(), 1, new AccessScope("t", "u", Set.of(1L)),
                "query", "query", now, now.plus(remaining), backend,
                new InMemoryRunEventStore(), new InMemoryEvidenceStore(), true);
        run.bindPersistedControl(16); run.start(); return run;
    }
    void awaitRelease(CountDownLatch release) {
        try { assertTrue(release.await(5, TimeUnit.SECONDS), "Test did not release blocked work"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    long done(RunState run) { return run.events().after(0).events().stream().filter(e -> e.type().equals("DONE")).count(); }

    @Test void blockedTerminalWriteCannotDelayAnotherRunsStopOrPublishAnEarlyDone() throws Exception {
        var writing = new CountDownLatch(1); var release = new CountDownLatch(1); var firstEnded = new CountDownLatch(1);
        var first = run(new RunBackend() {
            public Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
                writing.countDown(); awaitRelease(release);
                return RunBackend.super.commit(run, proposed, event, data, done);
            }
        }, Duration.ofMillis(100));
        first.onTerminal(s -> firstEnded.countDown());
        try (var control = new LoopControl(LoopPolicy.defaults())) {
            try {
                control.arm(first); assertTrue(writing.await(2, TimeUnit.SECONDS));
                var second = run(RunBackend.MEMORY, Duration.ofMillis(150));
                var secondStopped = new CountDownLatch(1); var secondEnded = new CountDownLatch(1);
                second.cancellation().onCancel(secondStopped::countDown); second.onTerminal(s -> secondEnded.countDown());
                control.arm(second);
                assertTrue(secondStopped.await(1, TimeUnit.SECONDS), "Another run's storage must not occupy the watchdog");
                assertTrue(secondEnded.await(1, TimeUnit.SECONDS));
                assertEquals(1, firstEnded.getCount()); assertEquals(0, done(first));
                assertEquals("DEADLINE_EXCEEDED", second.snapshot().errorCode()); assertEquals(1, done(second));
            } finally { release.countDown(); assertTrue(firstEnded.await(2, TimeUnit.SECONDS)); }
            assertEquals(1, done(first));
        }
    }
    @Test void slowTerminalHookCannotDelayAnotherRunsDeadline() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = run(RunBackend.MEMORY, Duration.ofSeconds(2));
        var tracer = OpenTelemetry.noop().getTracer("watchdog-test");
        AgentHook hook = new AgentHook() {
            public String name() { return "blocked-terminal"; }
            public Set<HookPoint> points() { return Set.of(HookPoint.ON_ERROR); }
            public void onEvent(HookEvent event) {
                if (event.runId().equals(first.id)) { entered.countDown(); awaitRelease(release); }
            }
        };
        try (var control = new LoopControl(LoopPolicy.defaults());
             var harness = new RuntimeHarness(tracer, new HookDispatcher(List.of(hook), tracer),
                     new TransformDispatcher(List.of(), tracer, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), control)) {
            try {
                harness.start(first); assertTrue(entered.await(3, TimeUnit.SECONDS));
                var second = run(RunBackend.MEMORY, Duration.ofMillis(150));
                var stopped = new CountDownLatch(1); second.cancellation().onCancel(stopped::countDown);
                control.arm(second);
                assertTrue(stopped.await(1, TimeUnit.SECONDS), "Slow Hook must not execute on the watchdog");
                assertEquals(Status.FAILED, first.snapshot().status()); assertEquals(1, done(first));
            } finally { release.countDown(); }
        }
    }
    @Test void watchdogDoesNotWaitForTheSameRunsStorageMonitor() throws Exception {
        var block = new AtomicBoolean(); var writing = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = run(new RunBackend() {
            public void save(RunState run) { if (block.get()) { writing.countDown(); awaitRelease(release); } }
        }, Duration.ofMillis(400));
        var stopped = new CountDownLatch(1); var ended = new CountDownLatch(1);
        first.cancellation().onCancel(stopped::countDown); first.onTerminal(s -> ended.countDown());
        var worker = Executors.newSingleThreadExecutor();
        try (var control = new LoopControl(LoopPolicy.defaults())) {
            try {
                control.arm(first); block.set(true);
                var save = worker.submit(first::nextStep); assertTrue(writing.await(1, TimeUnit.SECONDS));
                assertTrue(stopped.await(1, TimeUnit.SECONDS), "Watchdog must signal cancellation without acquiring RunState's storage-held monitor");
                assertEquals(0, done(first));
                release.countDown(); save.get(2, TimeUnit.SECONDS);
                assertTrue(ended.await(2, TimeUnit.SECONDS)); assertEquals(1, done(first));
                assertEquals("DEADLINE_EXCEEDED", first.snapshot().errorCode());
            } finally { release.countDown(); worker.shutdownNow(); }
        }
    }
    @Test void saturatedTerminationPoolKeepsWatchdogFreeAndMaintenanceFinishesTheRejectedRun() throws Exception {
        var writing = new CountDownLatch(1); var release = new CountDownLatch(1); var firstEnded = new CountDownLatch(1);
        var first = run(new RunBackend() {
            public Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
                writing.countDown(); awaitRelease(release);
                return RunBackend.super.commit(run, proposed, event, data, done);
            }
        }, Duration.ofMillis(100)); first.onTerminal(s -> firstEnded.countDown());
        var timer = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        timer.setThreadNamePrefix("isolation-watchdog-"); timer.setPoolSize(1); timer.initialize();
        var termination = com.tianmu.agent.config.ExecutionConfiguration.standalone("isolation-termination", 1, 1, 1);
        var control = new LoopControl(LoopPolicy.defaults(), timer,
                com.tianmu.agent.config.ExecutionConfiguration.standalone("isolation-model", 1, 1, 1),
                com.tianmu.agent.config.ExecutionConfiguration.standalone("isolation-tool", 1, 1, 1),
                com.tianmu.agent.config.ExecutionConfiguration.standalone("isolation-video", 1, 1, 1), termination, true);
        try (control) {
            try {
                control.arm(first); assertTrue(writing.await(2, TimeUnit.SECONDS));
                var second = run(RunBackend.MEMORY, Duration.ofMillis(100));
                var secondStopped = new CountDownLatch(1); second.cancellation().onCancel(secondStopped::countDown);
                var secondEnded = new CountDownLatch(1); second.onTerminal(s -> secondEnded.countDown());
                control.arm(second); assertTrue(secondStopped.await(1, TimeUnit.SECONDS));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (termination.getThreadPoolExecutor().getQueue().size() != 1 && System.nanoTime() < until) Thread.sleep(5);
                assertEquals(1, termination.getThreadPoolExecutor().getQueue().size());
                var commits = new AtomicInteger(); var committingThread = new AtomicReference<String>();
                var third = run(new RunBackend() {
                    public Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
                        commits.incrementAndGet(); committingThread.set(Thread.currentThread().getName());
                        return RunBackend.super.commit(run, proposed, event, data, done);
                    }
                }, Duration.ofMillis(100));
                var repository = mock(RunRepository.class);
                when(repository.create(anyString(), any(), any(), any(), any(), any())).thenReturn(new RunRepository.Created(third, true));
                var service = new RunService(mock(RequestOrchestrator.class), mock(RuntimeHarness.class), repository);
                try {
                    service.create("query", third.owner);
                    var stopped = new CountDownLatch(1); third.cancellation().onCancel(stopped::countDown);
                    control.arm(third); assertTrue(stopped.await(1, TimeUnit.SECONDS));
                    var timerFree = new CountDownLatch(1);
                    timer.schedule(timerFree::countDown, third.deadline().plusMillis(100));
                    assertTrue(timerFree.await(1, TimeUnit.SECONDS));
                    assertEquals(0, commits.get(), "Rejected termination must not run on the timer");
                    assertEquals(0, done(third)); assertEquals(1, service.pendingTerminals());
                    service.maintain();
                    assertEquals(Status.FAILED, third.snapshot().status()); assertEquals("DEADLINE_EXCEEDED", third.snapshot().errorCode());
                    assertEquals(1, done(third)); assertEquals(1, commits.get());
                    assertFalse(committingThread.get().startsWith("isolation-watchdog-"));
                    service.maintain(); assertEquals(1, commits.get()); assertEquals(0, third.snapshot().totalModelCalls());
                    release.countDown(); assertTrue(firstEnded.await(2, TimeUnit.SECONDS)); assertTrue(secondEnded.await(2, TimeUnit.SECONDS));
                } finally { service.shutdown(); }
            } finally { release.countDown(); }
        }
    }
    @Test void workerCancellationCannotReplaceTheWatchdogsTimeoutReasonOrPublishALateAnswer() {
        var run = run(RunBackend.MEMORY, Duration.ofSeconds(30));
        var ticket = run.reserveFinalResponse(); var callbacks = new AtomicInteger(); run.onTerminal(s -> callbacks.incrementAndGet());
        assertTrue(run.signalTimeout()); assertTrue(run.terminalPending());
        assertEquals(0, done(run)); assertEquals(Status.RUNNING, run.snapshot().status());
        assertFalse(run.succeed(ticket, new Answer("late", List.of()), com.tianmu.agent.core.StopReason.ANSWER));
        run.cancel(com.tianmu.agent.core.StopReason.SHUTDOWN);
        assertEquals(Status.FAILED, run.snapshot().status()); assertEquals("DEADLINE_EXCEEDED", run.snapshot().errorCode());
        assertFalse(run.signalTimeout()); run.timeout(); assertEquals(1, done(run)); assertEquals(1, callbacks.get());
    }
}
