package com.recsys.microservice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayAuthenticatorTest {

    @Test
    void disabledWhenNoApiKeysConfigured() throws Exception {
        GatewayAuthenticator authenticator = GatewayAuthenticator.fromEnvironment(Map.<String, String>of()::get);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertFalse(authenticator.isEnabled());
        assertTrue(authenticator.authenticate(request, response, "/api/model/recommend"));
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void acceptsApiKeyHeader() throws Exception {
        GatewayAuthenticator authenticator = GatewayAuthenticator.fromEnvironment(Map.of(
                "GATEWAY_API_KEYS", "alpha,beta"
        )::get);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-API-Key")).thenReturn(" beta ");

        assertTrue(authenticator.isEnabled());
        assertTrue(authenticator.authenticate(request, response, "/api/model/recommend"));
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void acceptsBearerToken() throws Exception {
        GatewayAuthenticator authenticator = GatewayAuthenticator.fromEnvironment(Map.of(
                "GATEWAY_API_KEYS", "alpha"
        )::get);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer alpha");

        assertTrue(authenticator.authenticate(request, response, "/api/model/recommend"));
    }

    @Test
    void rejectsMissingCredential() throws Exception {
        GatewayAuthenticator authenticator = GatewayAuthenticator.fromEnvironment(Map.of(
                "GATEWAY_API_KEYS", "alpha"
        )::get);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        assertFalse(authenticator.authenticate(request, response, "/api/model/recommend"));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void allowsConfiguredPublicPath() throws Exception {
        GatewayAuthenticator authenticator = GatewayAuthenticator.fromEnvironment(Map.of(
                "GATEWAY_API_KEYS", "alpha",
                "GATEWAY_PUBLIC_PATHS", "/health,/metrics"
        )::get);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertTrue(authenticator.authenticate(request, response, "/metrics/prometheus"));
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
