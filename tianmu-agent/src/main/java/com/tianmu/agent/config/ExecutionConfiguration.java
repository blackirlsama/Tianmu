package com.tianmu.agent.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.*;
import java.util.List;

@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfiguration {
    public static ThreadPoolTaskExecutor standalone(String name, int core, int max, int queue) {
        var executor = configured(name, core, max, queue); executor.initialize(); return executor;
    }
    private static ThreadPoolTaskExecutor configured(String name, int core, int max, int queue) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core); executor.setMaxPoolSize(max); executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(name + "-"); executor.setDaemon(true);
        executor.setAwaitTerminationSeconds(2); return executor;
    }
    private ThreadPoolTaskExecutor managed(String name, ExecutionProperties.Pool pool, MeterRegistry metrics) {
        var executor = configured(name, pool.core(), pool.max(), pool.queue());
        var rejected = metrics.counter("agent.executor.rejected", "pool", name);
        executor.setRejectedExecutionHandler((task, target) -> {
            rejected.increment(); throw new java.util.concurrent.RejectedExecutionException("Executor capacity exhausted");
        });
        return executor;
    }
    @Bean org.springframework.beans.factory.SmartInitializingSingleton executorMetrics(java.util.Map<String, ThreadPoolTaskExecutor> executors, MeterRegistry metrics) {
        return () -> executors.forEach((name, executor) -> ExecutorServiceMetrics.monitor(metrics, executor.getThreadPoolExecutor(), name, List.of()));
    }
    @Bean("runExecutor") ThreadPoolTaskExecutor runs(ExecutionProperties p, MeterRegistry m) { return managed("agent-runs", p.runs(), m); }
    @Bean("preparationExecutor") ThreadPoolTaskExecutor preparation(ExecutionProperties p, MeterRegistry m) { return managed("agent-preparation", p.preparation(), m); }
    @Bean("modelExecutor") ThreadPoolTaskExecutor model(ExecutionProperties p, MeterRegistry m) { return managed("agent-model", p.model(), m); }
    @Bean("toolExecutor") ThreadPoolTaskExecutor tools(ExecutionProperties p, MeterRegistry m) { return managed("agent-tools", p.tools(), m); }
    @Bean("videoExecutor") ThreadPoolTaskExecutor video(ExecutionProperties p, MeterRegistry m) { return managed("agent-video", p.video(), m); }
    @Bean("sseExecutor") ThreadPoolTaskExecutor sse(ExecutionProperties p, MeterRegistry m) { return managed("agent-sse", p.sse(), m); }
    @Bean("terminationExecutor") ThreadPoolTaskExecutor termination(ExecutionProperties p, MeterRegistry m) { return managed("agent-termination", p.termination(), m); }
    @Bean("deadlineScheduler") ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("agent-deadline-"); scheduler.setRemoveOnCancelPolicy(true); return scheduler;
    }
}
