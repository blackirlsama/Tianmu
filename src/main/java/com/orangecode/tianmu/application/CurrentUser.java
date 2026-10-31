package com.orangecode.tianmu.application;

import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Request identity populated only after JWT and online-session validation. */
@Component
public class CurrentUser {
    public static final String ATTRIBUTE = CurrentUser.class.getName() + ".userId";
    private final HttpServletRequest request;

    public CurrentUser(HttpServletRequest request) { this.request = request; }

    public long requireUserId() {
        Object value = request.getAttribute(ATTRIBUTE);
        if (!(value instanceof Long id) || id <= 0) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        return id;
    }

    /** Keep accepting the legacy field, but never let it choose the acting user. */
    public long requireMatchingUser(Long suppliedUserId) {
        long id = requireUserId();
        if (suppliedUserId != null && suppliedUserId != id) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return id;
    }
}
