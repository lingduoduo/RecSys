package com.recsys.model.config;

import com.recsys.model.service.LoginTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs on every request. Parses the Bearer token and populates RequestScopeData
 * with the authenticated userId. The NeedLoginAspect enforces access on methods
 * annotated with @NeedLogin.
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final LoginTokenService loginTokenService;
    private final RequestScopeData requestScopeData;

    public LoginInterceptor(LoginTokenService loginTokenService, RequestScopeData requestScopeData) {
        this.loginTokenService = loginTokenService;
        this.requestScopeData = requestScopeData;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String token = extractBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            return true;
        }
        try {
            String userId = loginTokenService.validate(token);
            requestScopeData.setLogin(true);
            requestScopeData.setUserId(userId);
            requestScopeData.setToken(token);
        } catch (Exception ignored) {
            // invalid/expired token — requestScopeData remains login=false
        }
        return true;
    }

    private static String extractBearer(String authorization) {
        if (authorization == null || authorization.isBlank()) return null;
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) return null;
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
