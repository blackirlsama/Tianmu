package com.orangecode.tianmu.interceptor;

import java.util.Arrays;
import java.util.List;

import com.orangecode.tianmu.utils.JwtUtil;
import com.orangecode.tianmu.application.CurrentUser;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JWTInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate redisTemplate;


    List<String> excludePaths = Arrays.asList(
            "/api/user/sendVerificationCode",
            "/api/user/register",
            "/api/user/info",
            "/api/user/loginCode",
            "/api/user/focus/list",
            "/api/user/fans/list",
            "/api/user/loginPassword",
            "/api/video/list",
            "/api/video/detail",
            "/api/video/comment/list",
            "/api/video/submit/list",
            "/api/video/coin/list",
            "/api/video/like/list",
            "/api/video/favorite/list",
            "/api/category",
            "/api/category/list",
            "/api/search/video",
            "/api/search/user"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        request.removeAttribute(CurrentUser.ATTRIBUTE);
        // 从请求头中获取token
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);

        // Public image URLs are restricted by MediaController to registered image files.
        if (request.getRequestURI().startsWith("/api/media/")) return true;

        if (excludePaths.contains(request.getRequestURI())) {
            return true;
        }

        // 身份只在签名与在线会话均验证成功后写入请求上下文。
        if (token != null && !token.isEmpty()) {
            Claims claims = JwtUtil.parse(token);
            if (claims != null && claims.getSubject() != null && claims.getSubject().matches("[1-9][0-9]{0,18}")) {
                String id = claims.getSubject();
                long userId;
                try { userId = Long.parseLong(id); }
                catch (NumberFormatException invalidSubject) {
                    return unauthorized(response);
                }
                String userToken = redisTemplate.opsForValue().get(id);
                boolean isToken = userToken != null && token.equals(userToken);
                if (isToken) {
                    request.setAttribute(CurrentUser.ATTRIBUTE, userId);
                    return true;
                }
            }
        }

        return unauthorized(response);
    }

    private boolean unauthorized(HttpServletResponse response) throws java.io.IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write("{\"code\": 401, \"message\": \"未授权的访问，请提供有效的Token\"}");

        return false;
    }
}
