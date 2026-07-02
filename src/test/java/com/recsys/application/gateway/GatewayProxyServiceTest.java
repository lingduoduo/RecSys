package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayProxyServiceTest {

    @Test
    void buildUpstreamHeaders_stripsSpoofedIdentityAndInjectsPrincipal() {
        RequestHeaders incoming = RequestHeaders.builder(HttpMethod.GET, "/api/model/predict")
                .add("x-authenticated-subject", "attacker")   // client-supplied spoof attempt
                .add("x-custom", "keep-me")
                .build();
        ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(incoming));
        GatewayPrincipal principal = GatewayPrincipal.ofApiKey("key-1");

        RequestHeaders upstream = GatewayProxyService.buildUpstreamHeaders(
                incoming, "/model/predict", ctx, principal);

        // Spoofed subject was stripped and NOT re-injected (api-key principal has no subject).
        assertNull(upstream.get("x-authenticated-subject"));
        // Principal's own identity header is injected.
        assertEquals("service", upstream.get("x-authenticated-client-id"));
        // A normal header is preserved.
        assertEquals("keep-me", upstream.get("x-custom"));
    }

    @Test
    void buildUpstreamHeaders_stripsGatewayConsumedCredentials() {
        RequestHeaders incoming = RequestHeaders.builder(HttpMethod.GET, "/api/model/predict")
                .add("x-api-key", "secret-key")
                .add("authorization", "Bearer secret.jwt.token")
                .add("x-custom", "keep-me")
                .build();
        ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(incoming));
        GatewayPrincipal principal = GatewayPrincipal.ofApiKey("secret-key");

        RequestHeaders upstream = GatewayProxyService.buildUpstreamHeaders(
                incoming, "/model/predict", ctx, principal);

        // The gateway's own credentials are consumed here, not forwarded to the backend.
        assertNull(upstream.get("x-api-key"));
        assertNull(upstream.get("authorization"));
        // Identity + normal headers still pass through.
        assertEquals("service", upstream.get("x-authenticated-client-id"));
        assertEquals("keep-me", upstream.get("x-custom"));
    }
}
