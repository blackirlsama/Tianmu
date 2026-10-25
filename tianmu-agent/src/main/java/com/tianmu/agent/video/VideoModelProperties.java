package com.tianmu.agent.video;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.video")
public record VideoModelProperties(@DefaultValue("false") boolean enabled, @DefaultValue("compatible") String provider, URI baseUrl,
        @DefaultValue("") String apiKey, @DefaultValue("") String model,
        @DefaultValue("") String transcriptionModel) {
    public VideoModelProperties {
        if (!java.util.Set.of("compatible", "minimax").contains(provider)) throw new IllegalArgumentException("Unsupported video provider");
        if (enabled && (baseUrl == null || !"https".equals(baseUrl.getScheme()) || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || apiKey.isBlank() || apiKey.contains("\n") || model.isBlank() || model.length() > 160))
            throw new IllegalArgumentException("Configure an HTTPS vision endpoint, model and private key");
    }
    @Override public String toString() { return "VideoModelProperties[enabled=" + enabled + "]"; }
}
