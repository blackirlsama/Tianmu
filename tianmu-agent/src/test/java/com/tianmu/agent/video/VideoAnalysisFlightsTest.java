package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class VideoAnalysisFlightsTest {
    static CallControl control() { return new CallControl(Instant.now().plusSeconds(10), new CancellationToken()); }
    static VideoContent.Facts facts() {
        return new VideoContent.Facts("video-content-v1", 7, "a".repeat(64), 0, 1000, "summary", List.of(), "",
                "SAMPLED_FRAMES", List.of(500L), "vision", Instant.now());
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AgentException("CANCELLED", "interrupted"); }
    }
    static void waiting(AtomicReference<Thread> thread) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            Thread t = thread.get();
            if (t != null && t.getState() == Thread.State.TIMED_WAITING) return;
            Thread.sleep(1);
        }
        fail("Follower did not enter bounded wait");
    }
    @Test void sameKeySharesOneResultAndDifferentKeysDoNotBlock() throws Exception {
        var flights = new VideoAnalysisFlights(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var followerThread = new AtomicReference<Thread>();
        var pool = Executors.newFixedThreadPool(3); var result = facts();
        try {
            var leader = pool.submit(() -> flights.run("one", control(), () -> { calls.incrementAndGet(); started.countDown(); await(release); return result; }));
            await(started);
            var follower = pool.submit(() -> { followerThread.set(Thread.currentThread()); return flights.run("one", control(), () -> fail("duplicate computation")); });
            waiting(followerThread);
            assertSame(result, pool.submit(() -> flights.run("two", control(), () -> result)).get(2, TimeUnit.SECONDS));
            release.countDown(); assertSame(result, leader.get(2, TimeUnit.SECONDS)); assertSame(result, follower.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void cancellingWaiterDoesNotCancelLeaderOrAnotherWaiter() throws Exception {
        var flights = new VideoAnalysisFlights(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>(); var cancelled = control(); var pool = Executors.newFixedThreadPool(3);
        try {
            var leader = pool.submit(() -> flights.run("key", control(), () -> { started.countDown(); await(release); return facts(); }));
            await(started);
            var waiter = pool.submit(() -> { thread.set(Thread.currentThread()); return flights.run("key", cancelled, () -> fail("duplicate")); });
            waiting(thread); cancelled.cancellation().cancel(StopReason.USER_CANCELLED);
            var error = assertThrows(ExecutionException.class, () -> waiter.get(2, TimeUnit.SECONDS));
            assertEquals("CANCELLED", ((AgentException) error.getCause()).code()); assertFalse(leader.isDone());
            var otherThread = new AtomicReference<Thread>();
            var other = pool.submit(() -> { otherThread.set(Thread.currentThread()); return flights.run("key", control(), () -> fail("duplicate")); });
            waiting(otherThread); release.countDown();
            assertEquals(leader.get(2, TimeUnit.SECONDS), other.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void stoppedLeaderAllowsWaiterToRetryWithItsOwnBudget() throws Exception {
        var flights = new VideoAnalysisFlights(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>(); var pool = Executors.newFixedThreadPool(2); var result = facts();
        try {
            var leader = pool.submit(() -> flights.run("key", control(), () -> { started.countDown(); await(release); throw new AgentException("MODEL_TOKEN_BUDGET_EXCEEDED", "leader only"); }));
            await(started);
            var follower = pool.submit(() -> { thread.set(Thread.currentThread()); return flights.run("key", control(), () -> result); });
            waiting(thread); release.countDown();
            assertThrows(ExecutionException.class, () -> leader.get(2, TimeUnit.SECONDS));
            assertSame(result, follower.get(2, TimeUnit.SECONDS));
            assertSame(result, flights.run("key", control(), () -> result), "Completed flights must be removed");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void deadlineStopsWaiterWithoutStoppingLeaderAndFailuresAreNotCached() throws Exception {
        var flights = new VideoAnalysisFlights(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var leader = pool.submit(() -> flights.run("key", control(), () -> { started.countDown(); await(release); throw new AgentException("VIDEO_MODEL_UNAVAILABLE", "failed"); }));
            await(started);
            var error = assertThrows(AgentException.class, () -> flights.run("key",
                    new CallControl(Instant.now().plusMillis(100), new CancellationToken()), () -> fail("duplicate")));
            assertEquals("VIDEO_MODEL_TIMEOUT", error.code()); assertFalse(leader.isDone());
            release.countDown(); assertThrows(ExecutionException.class, () -> leader.get(2, TimeUnit.SECONDS));
            assertNotNull(flights.run("key", control(), VideoAnalysisFlightsTest::facts));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void interruptedWaiterExitsImmediatelyInsteadOfRetryingTheFlight() throws Exception {
        var flights = new VideoAnalysisFlights(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>(); var pool = Executors.newFixedThreadPool(2);
        try {
            var leader = pool.submit(() -> flights.run("key", control(), () -> { started.countDown(); await(release); return facts(); }));
            await(started);
            var waiter = pool.submit(() -> {
                thread.set(Thread.currentThread());
                var error = assertThrows(AgentException.class, () -> flights.run("key", control(), () -> fail("duplicate")));
                assertTrue(Thread.currentThread().isInterrupted()); return error.code();
            });
            waiting(thread); thread.get().interrupt();
            assertEquals("CANCELLED", waiter.get(2, TimeUnit.SECONDS)); assertFalse(leader.isDone());
            release.countDown(); assertNotNull(leader.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
