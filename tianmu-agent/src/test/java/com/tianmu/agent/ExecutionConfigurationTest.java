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
}
