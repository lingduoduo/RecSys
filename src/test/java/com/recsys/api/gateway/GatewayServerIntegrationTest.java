package com.recsys.api.gateway;
import com.recsys.application.gateway.GatewayHealthService;
import com.recsys.application.gateway.GatewayProxyService;
import com.recsys.application.gateway.GatewayRequestForwarder;
import com.recsys.application.gateway.GatewayAuthenticator;
import com.recsys.application.gateway.MicroserviceRoute;
import com.recsys.application.gateway.RecommendationGatewayService;
import com.recsys.ratelimit.GatewayRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayServerIntegrationTest {

    // Reported as the gateway's own port in the /health per-port rollup (self-check).
    private static final int GATEWAY_SELF_PORT = 18010;

    // Fake upstream that identifies model requests and echoes the forwarded path and body.
    // Must be registered before `gateway` so its port is assigned first.
    @RegisterExtension
    @Order(1)
    static final ServerExtension fakeUpstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("prefix:/", (ctx, req) ->
                    com.linecorp.armeria.common.HttpResponse.of(req.aggregate().thenApply(aggregated ->
                            com.linecorp.armeria.common.HttpResponse.of(
                                    HttpStatus.OK,
                                    com.linecorp.armeria.common.MediaType.JSON_UTF_8,
                                    "{\"upstream\":\"model\",\"path\":\"" + ctx.path() + "\",\"body\":"
                                            + aggregated.contentUtf8() + "}"))));
        }
    };

    @RegisterExtension
    @Order(2)
    static final ServerExtension fakeOnlineUpstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("prefix:/", (ctx, req) ->
                    com.linecorp.armeria.common.HttpResponse.of(req.aggregate().thenApply(aggregated ->
                            com.linecorp.armeria.common.HttpResponse.of(
                                    HttpStatus.OK,
                                    com.linecorp.armeria.common.MediaType.JSON_UTF_8,
                                    "{\"upstream\":\"online\",\"path\":\"" + ctx.path() + "\",\"body\":"
                                            + aggregated.contentUtf8() + "}"))));
        }
    };

    // Gateway uses a lazy supplier for fakeUpstream's port so the port is
    // resolved at request time (after both extensions have started), not
    // at configure() call time (where fakeUpstream is not yet bound).
    @RegisterExtension
    @Order(3)
    static final ServerExtension gateway = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // Defer the port lookup via a dynamic base URI resolved on first request.
            // We build a placeholder here and rely on the fact that ServerExtension
            // starts fakeUpstream before gateway (declaration order).
            String base = "http://127.0.0.1:" + fakeUpstream.httpPort();
            List<MicroserviceRoute> routes = List.of(
                    new MicroserviceRoute("recsys", "/api/recsys", "UNUSED", URI.create(base), "/health"),
                    new MicroserviceRoute("model",  "/api/model",  "UNUSED", URI.create(base), "/health"),
                    new MicroserviceRoute("embed-recall", "/api/recommend/embedding", "UNUSED", URI.create(base), "/health"),
                    new MicroserviceRoute("model-inference", "/api/recommend/model", "UNUSED", URI.create(base), "/health"),
                    new MicroserviceRoute("online-blend", "/api/recommend/online", "UNUSED",
                            URI.create("http://127.0.0.1:" + fakeOnlineUpstream.httpPort()), "/health"),
                    new MicroserviceRoute("sequential", "/api/recommend/sequential", "UNUSED", URI.create(base), "/health"));
            Duration timeout = Duration.ofSeconds(3);
            Map<String, RouteCircuitBreaker> cbs = routes.stream()
                    .collect(Collectors.toMap(MicroserviceRoute::name,
                            r -> new RouteCircuitBreaker(3, 5000)));
            GatewayRateLimiter rateLimiter = GatewayRateLimiter.disabled();
            GatewayAuthenticator auth = GatewayAuthenticator.disabled();
            GatewayRequestForwarder forwarder =
                    new GatewayRequestForwarder(routes, timeout, cbs, rateLimiter);

            sb.service("/health", new GatewayHealthService(routes, timeout, cbs, GATEWAY_SELF_PORT))
              .service("/api/recommend", new RecommendationGatewayService(routes, forwarder, auth))
              .service("prefix:/", new GatewayProxyService(routes, forwarder, auth));
        }
    };

    @Test
    void healthReturns200() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/health");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("status");
    }

    @Test
    void healthIncludesPerPortRollupAndGatewaySelf() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/health");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        String body = r.contentUtf8();
        // Deduped per-port rollup is present...
        assertThat(body).contains("\"ports\"");
        // ...covers the backend port (both routes share fakeUpstream's port, collapsed to one entry)...
        assertThat(body).contains("\"" + fakeUpstream.httpPort() + "\":\"UP\"");
        // ...and the gateway reports its own port as a self-check.
        assertThat(body).contains("\"" + GATEWAY_SELF_PORT + "\":\"UP\"");
    }

    @Test
    void proxiesToUpstream() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/api/recsys/health");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"path\":\"/health\"");
    }

    @Test
    void canonicalRecommendationDefaultsToModel() {
        AggregatedHttpResponse r = gateway.blockingWebClient().post(
                "/api/recommend", "{\"userId\":42}");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"upstream\":\"model\"");
        assertThat(r.contentUtf8()).contains("\"path\":\"/v2/recommend\"");
        assertThat(r.contentUtf8()).contains("\"body\":{\"userId\":42}");
    }

    @Test
    void canonicalRecommendationRoutesExplicitOnlineStrategy() {
        AggregatedHttpResponse r = gateway.blockingWebClient().post(
                "/api/recommend", "{\"userId\":42,\"strategy\":\"online\"}");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"upstream\":\"online\"");
        assertThat(r.contentUtf8()).contains("\"path\":\"/v2/recommend\"");
        assertThat(r.contentUtf8()).contains("\"body\":{\"userId\":42}");
        assertThat(r.contentUtf8()).doesNotContain("strategy");
    }

    @Test
    void canonicalRecommendationRejectsNonPostOnExactRoute() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/api/recommend");
        assertThat(r.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(r.headers().get("allow")).isEqualTo("POST");
        assertThat(r.headers().contentType()).isEqualTo(com.linecorp.armeria.common.MediaType.JSON_UTF_8);
        assertThat(r.contentUtf8()).contains("\"error\"");
    }

    @Test
    void legacyModelAliasStillProxies() {
        AggregatedHttpResponse r = gateway.blockingWebClient().post(
                "/api/model/predict", "{\"userId\":42}");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"path\":\"/predict\"");
    }

    @Test
    void unmatchedRouteReturns404() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/no-such-route");
        assertThat(r.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.contentUtf8()).contains("error");
    }

    @Test
    void circuitBreakerOpenReturns503() {
        // Unit-style: verify CB blocks after threshold failures
        RouteCircuitBreaker cb = new RouteCircuitBreaker(1, 60_000);
        cb.recordFailure(cb.tryAcquirePermit());
        assertThat(cb.tryAcquirePermit()).isNull();
    }

    @Test
    void authRejectsNoKey() {
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(
                Map.of("GATEWAY_API_KEYS", "secret")::get);
        var result = auth.check(
                com.linecorp.armeria.common.RequestHeaders.of(HttpMethod.GET, "/api/recsys/health"),
                "/api/recsys/health");
        assertThat(result.rejected()).isTrue();
        assertThat(result.rejection().aggregate().join().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void versionedPathProxiesIdenticallyToUnversioned() {
        AggregatedHttpResponse unversioned = gateway.blockingWebClient().get("/api/recsys/health");
        AggregatedHttpResponse versioned = gateway.blockingWebClient().get("/api/v1/recsys/health");

        assertThat(versioned.status()).isEqualTo(unversioned.status());
        // The upstream echoes the path it was called with: the version segment must be gone.
        assertThat(versioned.contentUtf8()).isEqualTo(unversioned.contentUtf8());
        assertThat(versioned.contentUtf8()).contains("\"path\":\"/health\"");
    }

    @Test
    void unsupportedVersionIsRejectedWith400() {
        AggregatedHttpResponse response = gateway.blockingWebClient().get("/api/v2/recsys/health");

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("unsupported API version: v2");
        assertThat(response.contentUtf8()).contains("supported: v1");
    }

    @Test
    void pathThatMerelyLooksLikeAVersionIsNotStripped() {
        // "/api/v1x" is a resource segment, not v1 — it must not be stripped, so no route matches.
        AggregatedHttpResponse response = gateway.blockingWebClient().get("/api/v1x/recsys/health");

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
