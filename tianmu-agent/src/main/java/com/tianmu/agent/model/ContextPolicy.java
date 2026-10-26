package com.tianmu.agent.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Byte limits are deterministic; they are not a provider-specific token count. */
@ConfigurationProperties("agent.context")
public record ContextPolicy(@DefaultValue("98304") int maxInputBytes,
                            @DefaultValue("16384") int maxHistoryBytes) {
    public ContextPolicy {
        if (maxInputBytes < 1024 || maxInputBytes > 4_194_304
                || maxHistoryBytes < 0 || maxHistoryBytes > maxInputBytes)
            throw new IllegalArgumentException("Invalid model context limits");
    }
    public static ContextPolicy defaults() { return new ContextPolicy(98304, 16384); }
}
