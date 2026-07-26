package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.cache.LlmResponseCache;
import com.recsys.ratelimit.LlmTokenRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static com.recsys.resilience.RouteCircuitBreaker.State.HALF_OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmProxyServiceTest {

    @Test
    void streamingHalfOpenProbeWithSuccessfulHeadersAndBodyErrorDoesNotCloseCircuit()
            throws Exception {
        RouteCircuitBreaker circuit = new RouteCircuitBreaker(1, 0L);
        circuit.recordFailure(circuit.tryAcquirePermit());
        assertThat(circuit.state()).isEqualTo(RouteCircuitBreaker.State.HALF_OPEN);
        RouteCircuitBreaker.Permit probe = circuit.tryAcquirePermit();

        MicroserviceRoute route = new MicroserviceRoute(
                "llm", "/api/llm", "LLM_SERVICE_URL",
                URI.create("http://127.0.0.1:1"), "/health");
        LlmProxyService service = new LlmProxyService(
                route,
                Duration.ofSeconds(1),
                circuit,
                LlmTokenRateLimiter.disabled(),
                LlmResponseCache.disabled(),
                1_000,
                1_000L);
        HttpResponseWriter upstream = HttpResponse.streaming();
        var method = LlmProxyService.class.getDeclaredMethod(
                "forwardStreaming", HttpResponse.class, RouteCircuitBreaker.Permit.class);
        method.setAccessible(true);
        HttpResponse forwarded = (HttpResponse) method.invoke(service, upstream, probe);

        upstream.write(ResponseHeaders.of(HttpStatus.OK));
        assertThat(circuit.state()).isEqualTo(HALF_OPEN);
        upstream.close(new IllegalStateException("stream body failed"));

        assertThatThrownBy(() -> forwarded.aggregate().join())
                .hasRootCauseMessage("stream body failed");
        assertThat(circuit.state()).isEqualTo(HALF_OPEN);
    }

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
