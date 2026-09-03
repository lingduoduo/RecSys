package com.recsys.application.gateway;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.cache.LlmResponseCache;
import com.recsys.ratelimit.LlmTokenRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static com.recsys.resilience.RouteCircuitBreaker.State.HALF_OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmProxyServiceTest {

    // Echoes the path it actually received, so tests can verify what the upstream saw after
    // gateway-side rewriting/normalization.
    @RegisterExtension
    static final ServerExtension fakeLlmUpstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("prefix:/", (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated ->
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                            "{\"path\":\"" + ctx.path() + "\"}"))));
        }
    };

    /**
     * This class is a second forwarding path — it duplicates the credential stripping and identity
     * injection of {@code GatewayRequestForwarder} but never consults {@code BackendRoutePolicy}
     * for the request path at all: no user-scope check, no operator-token check, nothing. That is
     * safe only while no LLM route targets a service {@code BackendRoutePolicy} knows about at
     * all, regardless of which access class that service happens to declare — a narrower guard
     * that only checked for user-scoped routes would still miss a service whose routes are all
     * OPERATOR or NO_PROXY. The premise is enforced rather than assumed, so {@code 20_AuthN_AuthZ}
     * §10 can state it as a fact.
     */
    @Test
    void aRouteTargetingAUserScopedServiceIsRefusedAtConstruction() {
        MicroserviceRoute misrouted = new MicroserviceRoute(
                "llm", "/api/llm", "LLM_SERVICE_URL",
                URI.create("http://127.0.0.1:1"), "/health", "recsys-catalog-serving");

        assertThatThrownBy(() -> new LlmProxyService(
                misrouted, Duration.ofSeconds(1), new RouteCircuitBreaker(),
                LlmTokenRateLimiter.disabled(), LlmResponseCache.disabled(), 1_000, 1_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not consult BackendRoutePolicy");
    }

    /**
     * The realistic misconfiguration, and the one the serviceName guard alone could never catch:
     * {@code MicroserviceRoute.fromEnvOptional} — the only thing that builds an LLM route in
     * production — always passes {@code serviceName = null}. So pointing {@code LLM_SERVICE_URL} at
     * a backend produced a route the guard waved through, which then forwarded
     * {@code /api/llm/api/v1/recommend} to 8080 with no check at all.
     *
     * <p>The guard therefore resolves the route's <em>target</em>, not its label: a route with no
     * serviceName that points at the same authority as a declared backend is treated as that
     * backend.
     */
    @Test
    void anUnnamedRouteTargetingABackendAuthorityIsRefusedAtConstruction() {
        MicroserviceRoute misrouted = new MicroserviceRoute(
                "llm", "/api/llm", "LLM_SERVICE_URL",
                URI.create("http://localhost:8080"), "/health");   // 5-arg: serviceName is null

        assertThatThrownBy(() -> new LlmProxyService(
                misrouted, Duration.ofSeconds(1), new RouteCircuitBreaker(),
                LlmTokenRateLimiter.disabled(), LlmResponseCache.disabled(), 1_000, 1_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not consult BackendRoutePolicy");
    }

    /** A genuine LLM upstream shares no authority with any backend, so it still constructs. */
    @Test
    void aRouteTargetingARealLlmUpstreamStillConstructs() {
        MicroserviceRoute llm = new MicroserviceRoute(
                "llm", "/api/llm", "LLM_SERVICE_URL",
                URI.create("http://localhost:11434"), "/api/tags");

        new LlmProxyService(llm, Duration.ofSeconds(1), new RouteCircuitBreaker(),
                LlmTokenRateLimiter.disabled(), LlmResponseCache.disabled(), 1_000, 1_000L);
    }

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
                "forwardStreaming", ServiceRequestContext.class, HttpResponse.class,
                RouteCircuitBreaker.Permit.class, int.class);
        method.setAccessible(true);
        // The context only carries the event loop the SSE keepalive schedules on, and the declared
        // token estimate only matters to the budget settle-up that a disabled LlmTokenRateLimiter
        // ignores; this test is about circuit-breaker state.
        ServiceRequestContext streamCtx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.POST, "/api/llm/v1/chat"));
        HttpResponse forwarded =
                (HttpResponse) method.invoke(service, streamCtx, upstream, probe, 1_000);

        upstream.write(ResponseHeaders.of(HttpStatus.OK));
        assertThat(circuit.state()).isEqualTo(HALF_OPEN);
        upstream.close(new IllegalStateException("stream body failed"));

        assertThatThrownBy(() -> forwarded.aggregate().join())
                .hasRootCauseMessage("stream body failed");
        assertThat(circuit.state()).isEqualTo(HALF_OPEN);
    }

    @Test
    void versionedRequestReachesUpstreamWithVersionSegmentStripped() {
        // Registered LLM routes use the version-free prefix "/api/llm" (MicroserviceGatewayServer
        // .registerLlmRoutes). route.rewrite() requires an exact prefix match, so unless serve()
        // normalizes the versioned request path BEFORE calling rewrite(), a request to
        // "/api/v1/llm/chat" would fail matchesPrefix and throw instead of ever reaching the
        // upstream. This proves the version segment is stripped end-to-end.
        MicroserviceRoute route = new MicroserviceRoute(
                "llm", "/api/llm", "LLM_SERVICE_URL",
                URI.create("http://127.0.0.1:" + fakeLlmUpstream.httpPort()), "/health");
        LlmProxyService service = new LlmProxyService(
                route,
                Duration.ofSeconds(2),
                new RouteCircuitBreaker(),
                LlmTokenRateLimiter.disabled(),
                LlmResponseCache.disabled(),
                1_000,
                1_000L);

        RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/api/v1/llm/chat")
                .contentType(MediaType.JSON_UTF_8).build();
        HttpRequest request = HttpRequest.of(headers, HttpData.ofUtf8("{\"prompt\":\"hi\"}"));
        ServiceRequestContext ctx = ServiceRequestContext.of(request);

        AggregatedHttpResponse response = service.serve(ctx, request).aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        // The upstream echoes the path it actually received: the "/api/v1/llm" prefix must be
        // gone, leaving only the route-relative suffix.
        assertThat(response.contentUtf8()).contains("\"path\":\"/chat\"");
    }

    @Test
    void versionedRequestIsRejected401WhenPublicPathsUseVersionedSpelling() {
        // REGRESSION GUARD (same pattern as RecommendationGatewayServiceTest and
        // GatewayServerIntegrationTest.versionedProtectedPathIsRejected401WithAuthEnabled).
        // serve() must normalize the path (ApiVersion.parse) BEFORE calling authenticator.check.
        // The authenticator's public-path config here is deliberately the VERSIONED spelling
        // "/api/v1/llm/chat" (never the normalized "/api/llm/chat"), so the two orderings differ:
        //   - correct order:  authenticator.check(headers, "/api/llm/chat") -> not public -> 401
        //   - broken order:   authenticator.check(headers, "/api/v1/llm/chat") -> matches the
        //                     configured public path -> allowed anonymously -> NOT 401
        // If serve() is ever reordered, this test goes red.
        MicroserviceRoute route = new MicroserviceRoute(
                "llm", "/api/llm", "LLM_SERVICE_URL",
                URI.create("http://127.0.0.1:" + fakeLlmUpstream.httpPort()), "/health");
        GatewayAuthenticator authenticator = GatewayAuthenticator.forTesting(
                Set.of("valid-key"), Set.of("/api/v1/llm/chat"), null);
        LlmProxyService service = new LlmProxyService(
                route,
                Duration.ofSeconds(2),
                new RouteCircuitBreaker(),
                LlmTokenRateLimiter.disabled(),
                LlmResponseCache.disabled(),
                1_000,
                1_000L,
                authenticator);

        RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/api/v1/llm/chat")
                .contentType(MediaType.JSON_UTF_8).build();
        HttpRequest request = HttpRequest.of(headers, HttpData.ofUtf8("{\"prompt\":\"hi\"}"));
        ServiceRequestContext ctx = ServiceRequestContext.of(request);

        AggregatedHttpResponse response = service.serve(ctx, request).aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
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
