package com.recsys.api.gateway;

import com.recsys.application.gateway.ApiVersion;
import com.recsys.application.gateway.GatewayAuthenticator;
import com.recsys.application.gateway.GatewayRequestForwarder;
import com.recsys.application.gateway.MicroserviceRoute;
import com.recsys.application.gateway.RecommendationGatewayService;
import com.recsys.infrastructure.cache.LlmResponseCache;
import com.recsys.ratelimit.GatewayRateLimiter;
import com.recsys.ratelimit.LlmTokenRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MicroserviceGatewayServerTest {

    // registerLlmRoutes is the only seam MicroserviceGatewayServer exposes for testing route
    // registration without starting the whole server. This drives it against a real ServerBuilder
    // and inspects the built Server's route table directly, proving both the unversioned and
    // versioned LLM prefixes are registered — the same guarantee the /api/recommend twin relies on
    // (see MicroserviceGatewayServer.main, which has no equivalent extractable seam; that
    // registration remains covered only by GatewayServerIntegrationTest's end-to-end harness).
    @Test
    void registerLlmRoutesRegistersBothUnversionedAndVersionedPrefixes() {
        MicroserviceRoute llmRoute = new MicroserviceRoute(
                "llm", "/api/llm", "LLM_SERVICE_URL", URI.create("http://127.0.0.1:1"), "/health");

        ServerBuilder sb = Server.builder().http(0);
        MicroserviceGatewayServer.registerLlmRoutes(
                sb,
                List.of(llmRoute),
                ClientFactory.ofDefault(),
                Duration.ofSeconds(1),
                Map.of("llm", new RouteCircuitBreaker()),
                LlmTokenRateLimiter.disabled(),
                LlmResponseCache.disabled(),
                1_000,
                1_000L,
                GatewayAuthenticator.disabled());

        Server server = sb.build();
        List<String> patterns = server.serviceConfigs().stream()
                .map(ServiceConfig::route)
                .map(Route::patternString)
                .toList();

        String versionedPrefix = ApiVersion.versioned(ApiVersion.DEFAULT_VERSION, llmRoute.prefix());
        assertThat(versionedPrefix).isEqualTo("/api/v1/llm");
        assertThat(patterns).as("unversioned LLM prefix registered")
                .anyMatch(p -> p.startsWith("/api/llm/"));
        assertThat(patterns).as("versioned LLM prefix registered")
                .anyMatch(p -> p.startsWith(versionedPrefix + "/"));
    }

    @Test
    void registerRecommendRoutesRegistersBothUnversionedAndVersionedExactPaths() {
        List<MicroserviceRoute> routes = List.of();
        GatewayRequestForwarder forwarder = new GatewayRequestForwarder(
                routes, Duration.ofSeconds(1), Map.of(), GatewayRateLimiter.disabled());
        RecommendationGatewayService recommendationService = new RecommendationGatewayService(
                routes, forwarder, GatewayAuthenticator.disabled());

        ServerBuilder sb = Server.builder().http(0);
        MicroserviceGatewayServer.registerRecommendRoutes(sb, recommendationService);

        Server server = sb.build();
        List<String> patterns = server.serviceConfigs().stream()
                .map(ServiceConfig::route)
                .map(Route::patternString)
                .toList();

        assertThat(patterns).as("both recommend spellings registered")
                .contains("/api/recommend", "/api/v1/recommend");
    }
}
