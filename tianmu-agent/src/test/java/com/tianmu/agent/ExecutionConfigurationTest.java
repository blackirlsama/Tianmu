package com.tianmu.agent;

import com.tianmu.agent.config.ExecutionConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionConfigurationTest {
    @Test void saturatedVideoPoolDoesNotBlockModelsAndMetricsBindToManagedPools() {
        new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("agent.execution.video.core=1", "agent.execution.video.max=1", "agent.execution.video.queue=1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var video = context.getBean("videoExecutor", ThreadPoolTaskExecutor.class);
                    var model = context.getBean("modelExecutor", ThreadPoolTaskExecutor.class);
                    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                    try {
                        video.execute(() -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
                        assertTrue(entered.await(2, TimeUnit.SECONDS));
                        video.execute(() -> {});
                        assertThrows(RejectedExecutionException.class, () -> video.execute(() -> {}));
                        assertEquals("ready", model.submit(() -> "ready").get(2, TimeUnit.SECONDS));
                        assertEquals(1.0, context.getBean(MeterRegistry.class).get("executor.queued").tag("name", "videoExecutor").gauge().value());
                    } finally { release.countDown(); }
                });
    }
    @Test void terminationPoolIsBoundedObservableAndNeverRunsRejectedWorkOnTheCaller() {
        new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("agent.execution.termination.core=1", "agent.execution.termination.max=1", "agent.execution.termination.queue=1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var termination = context.getBean("terminationExecutor", ThreadPoolTaskExecutor.class);
                    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                    var callerRan = new java.util.concurrent.atomic.AtomicBoolean();
                    try {
                        termination.execute(() -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
                        assertTrue(entered.await(1, TimeUnit.SECONDS)); termination.execute(() -> {});
                        assertThrows(RejectedExecutionException.class, () -> termination.execute(() -> callerRan.set(true)));
                        assertFalse(callerRan.get());
                        assertEquals(1.0, context.getBean(MeterRegistry.class).get("agent.executor.rejected").tag("pool", "agent-termination").counter().count());
                        assertEquals(1.0, context.getBean(MeterRegistry.class).get("executor.queued").tag("name", "terminationExecutor").gauge().value());
                        assertEquals("ready", context.getBean("modelExecutor", ThreadPoolTaskExecutor.class).submit(() -> "ready").get(1, TimeUnit.SECONDS));
                    } finally { release.countDown(); }
                });
    }
}
