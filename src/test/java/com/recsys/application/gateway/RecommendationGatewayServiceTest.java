package com.recsys.application.gateway;

import com.recsys.ratelimit.GatewayRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationGatewayServiceTest {

    @RegisterExtension @Order(1)
    static final ServerExtension embedding = upstream("embed-recall");
    @RegisterExtension @Order(2)
    static final ServerExtension model = upstream("model-inference");
    @RegisterExtension @Order(3)
    static final ServerExtension online = upstream("online-blend");
    @RegisterExtension @Order(4)
    static final ServerExtension sequential = upstream("sequential");

    @RegisterExtension @Order(5)
    static final ServerExtension gateway = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            List<MicroserviceRoute> routes = List.of(
                    route("embed-recall", embedding),
                    route("model-inference", model),
                    route("online-blend", online),
                    route("sequential", sequential));
            Map<String, RouteCircuitBreaker> breakers = Map.of(
                    "embed-recall", new RouteCircuitBreaker(),
                    "model-inference", new RouteCircuitBreaker(),
                    "online-blend", new RouteCircuitBreaker(),
                    "sequential", new RouteCircuitBreaker());
            GatewayRequestForwarder forwarder = new GatewayRequestForwarder(
                    routes, Duration.ofSeconds(2), breakers, GatewayRateLimiter.disabled());
            sb.service("/api/recommend", new RecommendationGatewayService(
                    routes, forwarder, GatewayAuthenticator.disabled()));
        }
    };

    @RegisterExtension @Order(6)
    static final ServerExtension authenticatedGateway = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            List<MicroserviceRoute> routes = List.of(
                    route("embed-recall", embedding),
                    route("model-inference", model),
                    route("online-blend", online),
                    route("sequential", sequential));
            Map<String, RouteCircuitBreaker> breakers = Map.of(
                    "embed-recall", new RouteCircuitBreaker(),
                    "model-inference", new RouteCircuitBreaker(),
                    "online-blend", new RouteCircuitBreaker(),
                    "sequential", new RouteCircuitBreaker());
            GatewayRequestForwarder forwarder = new GatewayRequestForwarder(
                    routes, Duration.ofSeconds(2), breakers, GatewayRateLimiter.disabled());
            sb.service("/api/recommend", new RecommendationGatewayService(
                    routes, forwarder,
                    GatewayAuthenticator.forTesting(Set.of("valid-key"), Set.of(), null)));
        }
    };

    @ParameterizedTest
    @CsvSource({
            "embedding,embed-recall",
            "model,model-inference",
            "online,online-blend",
            "sequential,sequential",
            "'  ONLINE  ',online-blend"
    })
    void dispatchesSupportedStrategies(String strategy, String expectedRoute) {
        AggregatedHttpResponse response = postJson(
                "{\"userId\":42,\"strategy\":\"" + strategy + "\"}");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains(expectedRoute, "/v2/recommend");
    }

    @Test
    void defaultsToModelStrategy() {
        assertThat(postJson("{\"userId\":42}").contentUtf8()).contains("model-inference");
    }

    @Test
    void removesStrategyFromForwardedJson() {
        AggregatedHttpResponse response = postJson("{\"userId\":42,\"strategy\":\"online\"}");
        assertThat(response.contentUtf8()).contains("\"userId\":42").doesNotContain("strategy");
    }

    @ParameterizedTest
    @CsvSource({"''", "not-json", "'[]'"})
    void rejectsBodiesThatAreNotJsonObjects(String body) {
        assertBadRequest(body, "request body must be a JSON object");
    }

    @Test
    void rejectsTrailingJsonTokens() {
        assertBadRequest("{\"userId\":42} {\"strategy\":\"online\"}",
                "request body must be a JSON object");
    }

    @Test
    void rejectsNonStringStrategy() {
        assertBadRequest("{\"strategy\":3}", "strategy must be a string");
    }

    @Test
    void rejectsUnsupportedStrategyAndListsSupportedValues() {
        assertBadRequest("{\"strategy\":\"unknown\"}",
                "unsupported strategy; expected one of embedding, model, online, sequential");
    }

    @Test
    void rejectsNonPostMethodsWithJsonAndAllowHeader() {
        AggregatedHttpResponse response = gateway.webClient().execute(
                HttpRequest.of(HttpMethod.GET, "/api/recommend")).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(response.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("POST");
    }

    @Test
    void preservesRawQueryWhenDispatching() {
        AggregatedHttpResponse response = postJson(
                "/api/recommend?filter=a%2Fb&filter=c+d&empty=", "{\"userId\":42}");

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8())
                .contains("/v2/recommend?filter=a%2Fb&filter=c+d&empty=");
    }

    @Test
    void returnsAuthenticationRejectionUnchanged() {
        AggregatedHttpResponse response = authenticatedGateway.webClient().execute(
                HttpRequest.of(RequestHeaders.builder(HttpMethod.POST, "/api/recommend")
                                .contentType(MediaType.JSON_UTF_8).build(),
                        HttpData.ofUtf8("{\"userId\":42}"))).aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.headers().get(HttpHeaderNames.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(response.contentUtf8())
                .isEqualTo("{\"error\":\"missing or invalid gateway API key\"}");
    }

    @Test
    void rejectsMethodBeforeAuthentication() {
        AggregatedHttpResponse response = authenticatedGateway.webClient().execute(
                HttpRequest.of(HttpMethod.GET, "/api/recommend")).aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("POST");
    }

    @Test
    void reconstructsUpstreamHeadersAtAuthenticatedBoundary() {
        RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/api/recommend")
                .contentType(MediaType.JSON_UTF_8)
                .add("x-api-key", "valid-key")
                .add(HttpHeaderNames.AUTHORIZATION, "Bearer attacker-token")
                .add("x-authenticated-subject", "attacker")
                .add("x-authenticated-client-id", "attacker")
                .add("x-authenticated-token-use", "attacker")
                .add("x-request-id", "trace-123")
                .build();

        AggregatedHttpResponse response = authenticatedGateway.webClient().execute(
                HttpRequest.of(headers, HttpData.ofUtf8("{\"userId\":42}"))).aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8())
                .contains("apiKey=null", "authorization=null", "subject=null",
                        "clientId=service", "tokenUse=null", "requestId=trace-123");
    }

    private static ServerExtension upstream(String strategyName) {
        return new ServerExtension() {
            @Override
            protected void configure(ServerBuilder sb) {
                sb.service("prefix:/", (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(request ->
                        HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                strategyName + " " + ctx.path()
                                        + (ctx.query() == null ? "" : "?" + ctx.query())
                                        + " apiKey=" + request.headers().get("x-api-key")
                                        + " authorization=" + request.headers().get(HttpHeaderNames.AUTHORIZATION)
                                        + " subject=" + request.headers().get("x-authenticated-subject")
                                        + " clientId=" + request.headers().get("x-authenticated-client-id")
                                        + " tokenUse=" + request.headers().get("x-authenticated-token-use")
                                        + " requestId=" + request.headers().get("x-request-id")
                                        + " " + request.contentUtf8()))));
            }
        };
    }

    private static MicroserviceRoute route(String name, ServerExtension upstream) {
        return new MicroserviceRoute(name, "/unused/" + name, "UNUSED",
                URI.create("http://127.0.0.1:" + upstream.httpPort()), "/health");
    }

    private static AggregatedHttpResponse postJson(String body) {
        return postJson("/api/recommend", body);
    }

    private static AggregatedHttpResponse postJson(String path, String body) {
        RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, path)
                .contentType(MediaType.JSON_UTF_8).build();
        return gateway.webClient().execute(HttpRequest.of(headers, HttpData.ofUtf8(body)))
                .aggregate().join();
    }

    private static void assertBadRequest(String body, String message) {
        AggregatedHttpResponse response = postJson(body);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains(message);
    }
}
