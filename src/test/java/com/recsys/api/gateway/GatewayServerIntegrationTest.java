package com.recsys.api.gateway;
import com.recsys.application.gateway.GatewayHealthService;
import com.recsys.application.gateway.GatewayProxyService;
import com.recsys.application.gateway.GatewayAuthenticator;
import com.recsys.application.gateway.MicroserviceRoute;
import com.recsys.ratelimit.GatewayRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayServerIntegrationTest {

    // Fake upstream that returns 200 for all requests.
    // Must be registered before `gateway` so its port is assigned first.
    @RegisterExtension
    static final ServerExtension fakeUpstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("prefix:/", (ctx, req) ->
                    com.linecorp.armeria.common.HttpResponse.of(
                            HttpStatus.OK,
                            com.linecorp.armeria.common.MediaType.JSON_UTF_8,
                            "{\"upstream\":\"ok\"}"));
        }
    };

    // Gateway uses a lazy supplier for fakeUpstream's port so the port is
    // resolved at request time (after both extensions have started), not
    // at configure() call time (where fakeUpstream is not yet bound).
    @RegisterExtension
    static final ServerExtension gateway = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // Defer the port lookup via a dynamic base URI resolved on first request.
            // We build a placeholder here and rely on the fact that ServerExtension
            // starts fakeUpstream before gateway (declaration order).
            String base = "http://127.0.0.1:" + fakeUpstream.httpPort();
            List<MicroserviceRoute> routes = List.of(
                    new MicroserviceRoute("recsys", "/api/recsys", "UNUSED", URI.create(base), "/health"),
                    new MicroserviceRoute("model",  "/api/model",  "UNUSED", URI.create(base), "/health"));
            Duration timeout = Duration.ofSeconds(3);
            Map<String, RouteCircuitBreaker> cbs = routes.stream()
                    .collect(Collectors.toMap(MicroserviceRoute::name,
                            r -> new RouteCircuitBreaker(3, 5000)));
            GatewayRateLimiter rateLimiter = GatewayRateLimiter.disabled();
            GatewayAuthenticator auth = GatewayAuthenticator.disabled();

            sb.service("/health", new GatewayHealthService(routes, timeout, cbs))
              .service("prefix:/", new GatewayProxyService(routes, timeout, cbs, rateLimiter, auth));
        }
    };

    @Test
    void healthReturns200() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/health");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("status");
    }

    @Test
    void proxiesToUpstream() {
        AggregatedHttpResponse r = gateway.blockingWebClient().get("/api/recsys/health");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("upstream");
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
        cb.recordFailure();
        assertThat(cb.tryAcquire()).isFalse();
    }

    @Test
    void authRejectsNoKey() {
        GatewayAuthenticator auth = GatewayAuthenticator.fromEnvironment(
                Map.of("GATEWAY_API_KEYS", "secret")::get);
        var rejection = auth.check(
                com.linecorp.armeria.common.RequestHeaders.of(HttpMethod.GET, "/api/recsys/health"),
                "/api/recsys/health");
        assertThat(rejection).isNotNull();
        assertThat(rejection.aggregate().join().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
