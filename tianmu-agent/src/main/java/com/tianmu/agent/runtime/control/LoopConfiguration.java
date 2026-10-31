package com.tianmu.agent.runtime.control;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(LoopPolicy.class)
public class LoopConfiguration {
    @Bean(destroyMethod = "close") LoopControl loopControl(LoopPolicy policy,
            @org.springframework.beans.factory.annotation.Qualifier("deadlineScheduler") org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler timer,
            @org.springframework.beans.factory.annotation.Qualifier("modelExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor model,
            @org.springframework.beans.factory.annotation.Qualifier("toolExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor tools,
            @org.springframework.beans.factory.annotation.Qualifier("videoExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor video,
            @org.springframework.beans.factory.annotation.Qualifier("terminationExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor termination) {
        return new LoopControl(policy, timer, model, tools, video, termination, false);
    }
}
