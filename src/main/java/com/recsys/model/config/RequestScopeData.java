package com.recsys.model.config;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped bean populated by LoginInterceptor.
 * Holds the authenticated user's identity for the duration of a single request.
 */
@Component
@RequestScope
public class RequestScopeData {

    private boolean login = false;
    private String userId;
    private String token;

    public boolean isLogin() { return login; }
    public void setLogin(boolean login) { this.login = login; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
