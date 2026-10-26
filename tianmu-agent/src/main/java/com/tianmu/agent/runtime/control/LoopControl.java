package com.tianmu.agent.runtime.control;

import com.tianmu.agent.core.StopReason;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.RunState;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Independent watchdog and bounded blocking-call workers; neither depends on another model turn. */
public final class LoopControl implements AutoCloseable {
    private final LoopPolicy policy;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler timer;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor calls, tools, video;
    private final boolean owns;
    public LoopControl(LoopPolicy policy) {
        this(policy, scheduler(), com.tianmu.agent.config.ExecutionConfiguration.standalone("agent-model", 2, 4, 16),
                com.tianmu.agent.config.ExecutionConfiguration.standalone("agent-tools", 2, 4, 16),
                com.tianmu.agent.config.ExecutionConfiguration.standalone("agent-video", 2, 4, 8), true);
    }
    private static org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler(); scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true); scheduler.initialize(); return scheduler;
    }
    public LoopControl(LoopPolicy policy, org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler timer,
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor calls, org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor tools,
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor video, boolean owns) {
        this.policy = policy; this.timer = timer; this.calls = calls; this.tools = tools; this.video = video; this.owns = owns;
    }
    public LoopPolicy policy() { return policy; }
    public void arm(RunState run) {
        run.configureBudget(policy);
        if (!run.bindControl(policy.requestTimeout(), policy.maxModelCalls())) return;
        long millis = Math.max(0, Duration.between(Instant.now(), run.deadline()).toMillis());
        ScheduledFuture<?> deadline = timer.schedule(run::timeout, Instant.now().plusMillis(millis));
        run.onTerminal(snapshot -> deadline.cancel(false));
    }
    public <T> T call(RunState run, Duration timeout, String timeoutCode, String failureCode,
                       Function<Runnable, Runnable> contextual, Function<CallControl, T> operation) {
        return call(run, timeout, timeoutCode, failureCode, contextual, operation, (name, value) -> {});
    }
    public <T> T call(RunState run, Duration timeout, String timeoutCode, String failureCode,
                       Function<Runnable, Runnable> contextual, Function<CallControl, T> operation, BiConsumer<String, Long> timing) {
        return callOn(calls, run, timeout, timeoutCode, failureCode, contextual, operation, timing);
    }
    public <T> T toolCall(RunState run, boolean media, Duration timeout, Function<Runnable, Runnable> contextual, Function<CallControl,T> operation) {
        return toolCall(run, media, timeout, contextual, operation, (name, value) -> {});
    }
    public <T> T toolCall(RunState run, boolean media, Duration timeout, Function<Runnable, Runnable> contextual,
                         Function<CallControl,T> operation, BiConsumer<String, Long> timing) {
        return callOn(media ? video : tools, run, timeout, "TOOL_CALL_TIMEOUT", "TOOL_ERROR", contextual, operation, timing);
    }
    private <T> T callOn(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor, RunState run, Duration timeout, String timeoutCode, String failureCode,
            Function<Runnable, Runnable> contextual, Function<CallControl,T> operation, BiConsumer<String, Long> timing) {
        arm(run);
        run.checkpoint();
        Instant localDeadline = Instant.now().plus(timeout);
        long submittedAt = System.nanoTime();
        observe(timing, "execution.timeout_ms", Math.max(0, Math.min(timeout.toMillis(), Duration.between(Instant.now(), run.deadline()).toMillis())));
        CallControl control = run.callControl(localDeadline.isBefore(run.deadline()) ? localDeadline : run.deadline(), new CancellationToken());
        FutureTask<T> task = new FutureTask<>(() -> {
            long activeAt = System.nanoTime();
            observe(timing, "execution.queue_ms", Math.max(0, (activeAt - submittedAt) / 1_000_000));
            try { return operation.apply(control); }
            finally { observe(timing, "execution.active_ms", Math.max(0, (System.nanoTime() - activeAt) / 1_000_000)); }
        });
        Runnable submitted = contextual.apply(task);
        try (var registration = run.cancellation().onCancel(() -> {
            control.cancellation().cancel(run.cancellation().reason()); task.cancel(true); executor.getThreadPoolExecutor().remove(submitted);
        })) {
            try { executor.execute(submitted); }
            catch (RejectedExecutionException e) { throw new AgentException("CALL_CAPACITY_EXCEEDED", "Blocking-call workers are full"); }
            long remaining = Duration.between(Instant.now(), run.deadline()).toNanos();
            if (remaining <= 0) { run.timeout(); run.checkpoint(); }
            try {
                T value = task.get(Math.max(1, Math.min(remaining, timeout.toNanos())), TimeUnit.NANOSECONDS);
                run.checkpoint();
                return value;
            } catch (TimeoutException e) {
                control.cancellation().cancel(StopReason.ADAPTER_TIMEOUT);
                task.cancel(true);
                if (!localDeadline.isBefore(run.deadline()) || !Instant.now().isBefore(run.deadline())) { run.timeout(); run.checkpoint(); }
                throw new AgentException(timeoutCode, "Adapter call exceeded its time budget");
            } catch (CancellationException e) {
                run.checkpoint();
                throw new AgentException("CANCELLED", "Adapter call cancelled");
            } catch (InterruptedException e) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                run.cancel(StopReason.SHUTDOWN);
                throw new AgentException("CANCELLED", "Execution interrupted");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof AgentException domain) throw domain;
                throw new AgentException(failureCode, "Adapter call failed");
            }
        } finally {
            if (task.isCancelled()) executor.getThreadPoolExecutor().remove(submitted);
            observe(timing, "execution.wait_ms", Math.max(0, (System.nanoTime() - submittedAt) / 1_000_000)); }
    }
    private static void observe(BiConsumer<String, Long> timing, String name, long value) {
        try { timing.accept(name, value); }
        catch (RuntimeException ignored) { /* Telemetry failure must not change execution control. */ }
    }
    @Override public void close() { if (owns) { timer.shutdown(); calls.shutdown(); tools.shutdown(); video.shutdown(); } }
}
