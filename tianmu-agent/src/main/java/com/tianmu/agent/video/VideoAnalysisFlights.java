package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceAttributes;
import com.tianmu.agent.runtime.control.CallControl;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Same-key work runs on the leader's existing worker and budget; waiters never cancel it. */
@Component
public final class VideoAnalysisFlights {
    private final com.github.benmanes.caffeine.cache.Cache<String, CompletableFuture<VideoContent.Facts>> active =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().build();
    private final Semaphore capacity = new Semaphore(128);
    private static final Set<String> LEADER_STOPPED = Set.of("CANCELLED", "DEADLINE_EXCEEDED", "VIDEO_MODEL_TIMEOUT",
            "MODEL_CALL_BUDGET_EXCEEDED", "MODEL_TOKEN_BUDGET_EXCEEDED");

    public VideoContent.Facts run(String key, CallControl control, Supplier<VideoContent.Facts> work) {
        while (true) {
            checkpoint(control);
            var elected = new java.util.concurrent.atomic.AtomicBoolean();
            CompletableFuture<VideoContent.Facts> future = active.get(key, ignored -> {
                if (!capacity.tryAcquire()) throw new AgentException("VIDEO_ANALYSIS_CAPACITY_EXCEEDED", "Too many distinct video analyses");
                elected.set(true); return new CompletableFuture<>();
            });
            boolean leader = elected.get();
            TraceAttributes.apply(Span.current(), Map.of("video.shared_wait", !leader));
            if (leader) {
                try {
                    var result = work.get(); checkpoint(control);
                    future.complete(result); return result;
                } catch (RuntimeException | Error error) {
                    future.completeExceptionally(error); throw error;
                } finally { active.asMap().remove(key, future); capacity.release(); }
            }
            try { return await(future, control); }
            catch (AgentException error) {
                checkpoint(control);
                if (Thread.currentThread().isInterrupted()) throw error;
                if (!LEADER_STOPPED.contains(error.code())) throw error;
                // A leader's cancellation/allowance belongs to that run, not to another caller.
                active.asMap().remove(key, future);
            }
        }
    }

    private VideoContent.Facts await(CompletableFuture<VideoContent.Facts> future, CallControl control) {
        long started = System.nanoTime();
        try {
            while (true) {
                checkpoint(control);
                try {
                    var result = future.get(Math.min(50, Math.max(1, Duration.between(Instant.now(), control.deadline()).toMillis())), TimeUnit.MILLISECONDS);
                    checkpoint(control); return result;
                } catch (TimeoutException ignored) { /* bounded wait permits independent cancellation */ }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AgentException("CANCELLED", "Video analysis wait interrupted"); }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof AgentException domain) throw domain;
                    if (e.getCause() instanceof Error error) throw error;
                    throw new AgentException("VIDEO_MODEL_UNAVAILABLE", "Shared video analysis failed");
                }
            }
        } finally { TraceAttributes.apply(Span.current(), Map.of("video.shared_wait_ms", (System.nanoTime() - started) / 1_000_000)); }
    }

    static void checkpoint(CallControl control) {
        control.checkpoint();
        if (!Instant.now().isBefore(control.deadline())) throw new AgentException("VIDEO_MODEL_TIMEOUT", "Video analysis deadline exceeded");
    }
}
