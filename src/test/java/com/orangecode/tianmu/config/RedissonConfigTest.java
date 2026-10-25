package com.orangecode.tianmu.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RedissonConfigTest {
    @Test void acceptsSpringRedisDurationFormats() {
        for (String value : new String[]{"5000ms", "5s", "5000"}) {
            var source = new MapConfigurationPropertySource(Map.of("spring.data.redis.timeout", value));
            var config = new Binder(source).bind("spring.data.redis", Bindable.of(RedissonConfig.class)).get();
            assertEquals(Duration.ofSeconds(5), config.getTimeout());
        }
    }
}
