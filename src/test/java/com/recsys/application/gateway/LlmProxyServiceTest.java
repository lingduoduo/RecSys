package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmProxyServiceTest {

    @Test
    void buildUpstreamHeaders_stripsSpoofedIdentityAndInjectsPrincipal() {
        RequestHeaders incoming = RequestHeaders.builder(HttpMethod.POST, "/api/llm/explain")
                .add("x-authenticated-subject", "attacker")   // client-supplied spoof attempt
                .add("x-custom", "keep-me")
                .build();
        ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(incoming));
        GatewayPrincipal principal = GatewayPrincipal.ofApiKey("key-1");

        RequestHeaders upstream = LlmProxyService.buildUpstreamHeaders(
                incoming, "/llm/explain", ctx, principal);

        // Spoofed subject was stripped and NOT re-injected (api-key principal has no subject).
        assertNull(upstream.get("x-authenticated-subject"));
        // Principal's own identity header is injected.
        assertEquals("service", upstream.get("x-authenticated-client-id"));
        // A normal header is preserved.
        assertEquals("keep-me", upstream.get("x-custom"));
    }

    @Test
    void buildUpstreamHeaders_stripsGatewayConsumedCredentials() {
        RequestHeaders incoming = RequestHeaders.builder(HttpMethod.POST, "/api/llm/explain")
                .add("x-api-key", "secret-key")
                .add("authorization", "Bearer secret.jwt.token")
                .add("x-custom", "keep-me")
                .build();
        ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(incoming));
        GatewayPrincipal principal = GatewayPrincipal.ofApiKey("secret-key");

        RequestHeaders upstream = LlmProxyService.buildUpstreamHeaders(
                incoming, "/llm/explain", ctx, principal);

        // The gateway's own credentials are consumed here, not forwarded to the backend.
        assertNull(upstream.get("x-api-key"));
        assertNull(upstream.get("authorization"));
        // Identity + normal headers still pass through.
        assertEquals("service", upstream.get("x-authenticated-client-id"));
        assertEquals("keep-me", upstream.get("x-custom"));
    }

    @Test
    void buildUpstreamHeaders_stripsOriginSecret() {
        RequestHeaders incoming = RequestHeaders.builder(HttpMethod.POST, "/api/llm/explain")
                .add(GatewayOriginSecret.HEADER, "cdn-only-secret")
                .add("x-custom", "keep-me")
                .build();
        ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(incoming));
        GatewayPrincipal principal = GatewayPrincipal.ofApiKey("secret-key");

        RequestHeaders upstream = LlmProxyService.buildUpstreamHeaders(
                incoming, "/llm/explain", ctx, principal);

        // The CloudFront origin secret must never reach the LLM upstream — LLM_SERVICE_URL is
        // operator-configurable and may point at a third-party hosted API whose request logs
        // we do not control.
        assertNull(upstream.get(GatewayOriginSecret.HEADER));
        // Normal headers still pass through.
        assertEquals("keep-me", upstream.get("x-custom"));
    }
}
