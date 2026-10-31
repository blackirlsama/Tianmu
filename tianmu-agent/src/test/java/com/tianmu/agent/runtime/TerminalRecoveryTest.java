package com.tianmu.agent.runtime;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.runtime.events.InMemoryRunEventStore;
import com.tianmu.agent.runtime.store.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerminalRecoveryTest {
    final AccessScope owner = new AccessScope("t", "u", Set.of(1L));
    RunState run(RunBackend backend, Duration remaining) {
        Instant now = Instant.now();
        var run = new RunState(UUID.randomUUID(), UUID.randomUUID(), 1, owner, "query", "query", now,
                now.plus(remaining), backend, new InMemoryRunEventStore(), new InMemoryEvidenceStore(), true);
        run.bindPersistedControl(16);
        return run;
    }
    @Test void localCancellationDoesNotWaitForStorageAndCannotPublishAnUncommittedTerminal() throws Exception {
        var committing = new CountDownLatch(1); var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var run = run(new RunBackend() {
            public Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
                committing.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                throw new AgentException("STORAGE_UNAVAILABLE", "injected");
            }
        }, Duration.ofSeconds(30));
        run.start(); run.cancellation().onCancel(stopped::countDown);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var cancelled = worker.submit(() -> assertThrows(AgentException.class, run::cancel));
            assertTrue(committing.await(1, TimeUnit.SECONDS));
            assertTrue(stopped.await(300, TimeUnit.MILLISECONDS), "Local stop must precede blocking storage I/O");
            release.countDown(); cancelled.get(2, TimeUnit.SECONDS);
            assertEquals(Status.RUNNING, run.snapshot().status());
            assertTrue(run.events().after(0).events().stream().noneMatch(e -> e.type().equals("DONE")));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void maintenanceConvergesAfterOutageAcrossDeadlineWithoutRerunningWork() throws Exception {
        var broken = new AtomicBoolean(true); var attempts = new AtomicInteger();
        var run = run(new RunBackend() {
            public Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
                attempts.incrementAndGet();
                if (broken.get()) throw new AgentException("STORAGE_UNAVAILABLE", "injected");
                return RunBackend.super.commit(run, proposed, event, data, done);
            }
        }, Duration.ofMillis(300));
        var executed = new CountDownLatch(1); var terminal = new AtomicInteger();
        run.onTerminal(s -> terminal.incrementAndGet());
        var repository = mock(RunRepository.class);
        when(repository.create(anyString(), any(), any(), any(), any(), any())).thenReturn(new RunRepository.Created(run, true));
        var orchestrator = mock(RequestOrchestrator.class);
        doAnswer(invocation -> {
            try { run.start(); run.fail("MODEL_ERROR"); }
            catch (AgentException expected) { /* The worker exits while storage is down. */ }
            finally { executed.countDown(); }
            return null;
        }).when(orchestrator).execute(run);
        try (var control = new LoopControl(LoopPolicy.defaults())) {
            control.arm(run);
            var service = new RunService(orchestrator, mock(RuntimeHarness.class), repository);
            try {
                service.create("query", owner);
                assertTrue(executed.await(1, TimeUnit.SECONDS));
                Thread.sleep(450); // Keep the outage across the original one-shot deadline.
                assertEquals(Status.RUNNING, run.snapshot().status());
                broken.set(false);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime() < until) {
                    service.maintain(); Thread.sleep(20);
                }
                assertEquals(Status.FAILED, run.snapshot().status());
                assertEquals("MODEL_ERROR", run.snapshot().errorCode(), "The first stop intent must survive secondary timeout/errors");
                assertEquals(1, terminal.get());
                assertEquals(List.of("ERROR", "DONE"), run.events().after(0).events().stream()
                        .filter(e -> !e.type().equals("PROGRESS")).map(Event::type).toList());
                verify(orchestrator, times(1)).execute(run);
                service.maintain(); assertEquals(1, terminal.get());
                assertTrue(attempts.get() >= 2);
            } finally { service.shutdown(); }
        }
    }
    @Test void repeatedErrorsDoNotReplaceCancellationOrHammerStorageAndLateAnswersCannotWin() {
        var broken = new AtomicBoolean(true); var attempts = new AtomicInteger();
        var run = run(new RunBackend() {
            public Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
                attempts.incrementAndGet();
                if (broken.get()) throw new AgentException("STORAGE_UNAVAILABLE", "injected");
                return RunBackend.super.commit(run, proposed, event, data, done);
            }
        }, Duration.ofSeconds(30));
        run.start(); var answerTicket = run.reserveFinalResponse();
        assertThrows(AgentException.class, run::cancel);
        assertEquals(StopReason.USER_CANCELLED, run.cancellation().reason());
        run.fail("CANCELLED"); run.timeout(); run.cancel();
        run.reconcileTerminal(Instant.now());
        assertEquals(1, attempts.get());
        assertFalse(run.succeed(answerTicket, new Answer("late", List.of()), StopReason.ANSWER));
        Instant firstRetry = run.terminalReconciliationAt();
        assertThrows(AgentException.class, () -> run.reconcileTerminal(firstRetry));
        assertEquals(2, attempts.get());
        assertTrue(run.terminalReconciliationAt().isAfter(firstRetry.plusSeconds(1)));
        broken.set(false); run.reconcileTerminal(run.terminalReconciliationAt());
        assertEquals(Status.CANCELLED, run.snapshot().status()); assertFalse(run.terminalPending());
        assertEquals(List.of("ABORT", "DONE"), run.events().after(0).events().stream()
                .filter(e -> !e.type().equals("PROGRESS")).map(Event::type).toList());
    }
    @Test void cancellationUsesTheLiveRunAfterAuthorizingTheStoredProjection() {
        var run = run(RunBackend.MEMORY, Duration.ofSeconds(30)); run.start();
        var projection = new RunState(run.id, run.sessionId, run.turnSeq, owner, run.message, run.query(), run.createdAt,
                run.deadline(), RunBackend.MEMORY, new InMemoryRunEventStore(), new InMemoryEvidenceStore(), true);
        projection.restore(run.snapshot());
        var repository = mock(RunRepository.class);
        when(repository.create(anyString(), any(), any(), any(), any(), any())).thenReturn(new RunRepository.Created(run, true));
        when(repository.load(run.id, owner)).thenReturn(projection);
        var orchestrator = mock(RequestOrchestrator.class);
        var service = new RunService(orchestrator, mock(RuntimeHarness.class), repository);
        try {
            service.create("query", owner);
            assertEquals(Status.CANCELLED, service.cancel(run.id, owner).status());
            assertTrue(run.cancellation().cancelled()); assertFalse(projection.cancellation().cancelled());
            verify(repository).load(run.id, owner);
            var foreign = new AccessScope("t", "other", owner.videoIds());
            when(repository.load(run.id, foreign)).thenReturn(projection);
            assertEquals("RUN_NOT_FOUND", assertThrows(AgentException.class, () -> service.cancel(run.id, foreign)).code());
        } finally { service.shutdown(); }
    }
    @Test void aLostCommitAcknowledgementCannotReplaceTheAlreadyCommittedAnswerWithAnError() {
        var saved = new AtomicReference<Snapshot>();
        var run = run(new RunBackend() {
            public Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
                if (saved.get() != null) return saved.get();
                saved.set(RunBackend.super.commit(run, proposed, event, data, done));
                throw new AgentException("STORAGE_UNAVAILABLE", "commit acknowledgement lost");
            }
        }, Duration.ofSeconds(30));
        run.start(); var terminal = new AtomicInteger(); run.onTerminal(s -> terminal.incrementAndGet());
        assertThrows(AgentException.class, () -> run.succeed(new Answer("committed", List.of())));
        assertEquals(Status.RUNNING, run.snapshot().status());
        run.fail("STORAGE_UNAVAILABLE");
        assertEquals(Status.SUCCEEDED, run.snapshot().status()); assertEquals("committed", run.snapshot().answer().text());
        assertFalse(run.terminalPending()); assertEquals(1, terminal.get());
        assertEquals(List.of("ANSWER", "DONE"), run.events().after(0).events().stream()
                .filter(e -> !e.type().equals("PROGRESS")).map(Event::type).toList());
    }
    @Test void purgedRunReleasesLocalResourcesWithoutInventingATerminalEvent() {
        var run = run(RunBackend.MEMORY, Duration.ofSeconds(30)); run.start();
        var terminal = new AtomicInteger(); run.onTerminal(s -> terminal.incrementAndGet());
        var ticket = run.reserveFinalResponse();
        run.discard();
        assertTrue(run.cancellation().cancelled()); assertFalse(run.terminalPending());
        assertEquals(Instant.MAX, run.terminalReconciliationAt());
        assertFalse(run.succeed(ticket, new Answer("late", List.of()), StopReason.ANSWER));
        run.fail("CANCELLED"); run.reconcileTerminal(Instant.now());
        assertEquals(0, terminal.get());
        assertTrue(run.events().after(0).events().stream().noneMatch(e -> e.type().equals("DONE")));
    }
    @Test void oneUnavailableRunCannotBlockOtherTerminationsOrRetryEveryTick() {
        var unavailable = run(RunBackend.MEMORY, Duration.ofSeconds(-1));
        var healthy = run(RunBackend.MEMORY, Duration.ofSeconds(-1));
        var repository = mock(RunRepository.class);
        when(repository.create(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new RunRepository.Created(unavailable, true), new RunRepository.Created(healthy, true));
        doThrow(new AgentException("STORAGE_UNAVAILABLE", "injected")).when(repository).refresh(unavailable);
        var service = new RunService(mock(RequestOrchestrator.class), mock(RuntimeHarness.class), repository);
        try {
            service.create("first", owner); service.create("second", owner); service.maintain();
            assertTrue(unavailable.terminalPending()); assertTrue(unavailable.cancellation().cancelled());
            assertEquals(Status.FAILED, healthy.snapshot().status()); assertEquals(1, service.pendingTerminals());
            service.maintain();
            verify(repository, times(1)).refresh(unavailable);
            assertEquals("DEADLINE_EXCEEDED", healthy.snapshot().errorCode());
        } finally { service.shutdown(); }
    }
}
