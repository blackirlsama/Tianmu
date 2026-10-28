package com.tianmu.agent.model.deepseek;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekModelDefaultsTest {
    @Test void loopDefaultsToV4Pro() {
        var source=new MapConfigurationPropertySource(Map.of("agent.models.api-key","fixture"));
        var properties=new Binder(source).bind("agent.models",Bindable.of(DeepSeekProperties.class)).get();
        assertEquals("deepseek-v4-pro",properties.harnessModel());
    }
    @Test void explicitDeploymentOverridesRemainSupported() {
        var source=new MapConfigurationPropertySource(Map.of("agent.models.api-key","fixture",
                "agent.models.harness-model","deployment-model"));
        var properties=new Binder(source).bind("agent.models",Bindable.of(DeepSeekProperties.class)).get();
        assertEquals("deployment-model",properties.harnessModel());
    }
}
