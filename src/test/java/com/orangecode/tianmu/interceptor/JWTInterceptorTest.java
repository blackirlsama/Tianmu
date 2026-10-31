package com.orangecode.tianmu.interceptor;

import com.orangecode.tianmu.application.CurrentUser;
import com.orangecode.tianmu.utils.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JWTInterceptorTest {
    private String original;
    private JWTInterceptor interceptor;
    private ValueOperations<String, String> values;

    @BeforeEach void setup() {
        original = System.getProperty("tianmu.jwt.secret-base64");
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        System.setProperty("tianmu.jwt.secret-base64", Base64.getEncoder().encodeToString(key));
        var redis = mock(StringRedisTemplate.class); values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        interceptor = new JWTInterceptor(); ReflectionTestUtils.setField(interceptor, "redisTemplate", redis);
    }

    @AfterEach void restore() {
        if (original == null) System.clearProperty("tianmu.jwt.secret-base64");
        else System.setProperty("tianmu.jwt.secret-base64", original);
    }

    @Test void verifiedTokenBindsIdentityWithBothSupportedHeaderFormats() throws Exception {
        String token = JwtUtil.generate("1"); when(values.get("1")).thenReturn(token);
        for (String header : new String[]{token, "Bearer " + token}) {
            var request = request(header); var response = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(request, response, new Object()));
            assertEquals(1L, new CurrentUser(request).requireUserId());
        }
    }

    @Test void revokedSessionRejectsRequestAndClearsStaleIdentity() throws Exception {
        var request = request(JwtUtil.generate("1")); request.setAttribute(CurrentUser.ATTRIBUTE, 2L);
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus()); assertNull(request.getAttribute(CurrentUser.ATTRIBUTE));
    }

    @Test void malformedOrOverflowingSubjectsCannotBecomeIdentity() throws Exception {
        for (String subject : new String[]{"wrong", "0", "9999999999999999999"}) {
            var request = request(JwtUtil.generate(subject));
            var response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request, response, new Object()));
            assertEquals(401, response.getStatus()); assertNull(request.getAttribute(CurrentUser.ATTRIBUTE));
        }
    }

    @Test void publicEndpointDoesNotAcquireUnverifiedIdentity() throws Exception {
        var request = request("invalid"); request.setRequestURI("/api/video/list");
        request.setAttribute(CurrentUser.ATTRIBUTE, 2L);
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertNull(request.getAttribute(CurrentUser.ATTRIBUTE)); verifyNoInteractions(values);
    }

    @Test void missingOrInvalidTokenCannotPopulateIdentity() throws Exception {
        for (String header : new String[]{"", "invalid"}) {
            var request = request(header); var response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request, response, new Object()));
            assertEquals(401, response.getStatus()); assertNull(request.getAttribute(CurrentUser.ATTRIBUTE));
        }
        verifyNoInteractions(values);
    }

    @Test void replacedOnlineTokenRevokesThePreviousLogin() throws Exception {
        when(values.get("1")).thenReturn("replacement-session-token");
        var request = request(JwtUtil.generate("1")); var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus()); assertNull(request.getAttribute(CurrentUser.ATTRIBUTE));
    }

    private MockHttpServletRequest request(String header) {
        var request = new MockHttpServletRequest("POST", "/api/video/like");
        request.addHeader("Authorization", header); return request;
    }
}
