package com.tianmu.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.execution")
public record ExecutionProperties(@DefaultValue Pool runs, @DefaultValue Pool preparation,
        @DefaultValue Pool model, @DefaultValue Pool tools, @DefaultValue Pool video, @DefaultValue Pool sse) {
    public record Pool(@DefaultValue("2") int core, @DefaultValue("4") int max, @DefaultValue("32") int queue) {
        public Pool {
            if (core < 1 || max < core || max > 128 || queue < 1 || queue > 4096)
                throw new IllegalArgumentException("Invalid executor capacity");
        }
    }
}
