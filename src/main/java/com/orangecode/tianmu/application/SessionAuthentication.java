package com.orangecode.tianmu.application;

import com.orangecode.tianmu.utils.JwtUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Shared JWT and online-session rule for HTTP and WebSocket ingress. */
@Component
public class SessionAuthentication {
    private final StringRedisTemplate redis;
    public SessionAuthentication(StringRedisTemplate redis) { this.redis = redis; }

    public Long authenticate(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        if (token == null || token.isBlank() || token.length() > 8192) return null;
        try {
            var claims = JwtUtil.parse(token);
            if (claims == null || claims.getSubject() == null || !claims.getSubject().matches("[1-9][0-9]{0,18}")) return null;
            long userId = Long.parseLong(claims.getSubject());
            return token.equals(redis.opsForValue().get(claims.getSubject())) ? userId : null;
        } catch (IllegalArgumentException invalidTokenOrSubject) { return null; }
    }
}
