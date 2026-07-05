package com.recsys.api.gateway;

import com.recsys.application.gateway.GatewayAuthenticator;
import com.recsys.application.gateway.MicroserviceRoute;
import com.recsys.infrastructure.cache.LlmResponseCache;
import com.recsys.ratelimit.LlmTokenRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class LlmGatewayWarmupIntegrationTest {

    static final List<String> hits = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final ServerExtension upstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("prefix:/", (ctx, req) -> {
                hits.add(ctx.path());
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{\"ok\":true}");
            });
        }
    };

    private static MicroserviceRoute llmRoute() {
        return new MicroserviceRoute("llm", "/api/llm", "UNUSED",
                URI.create("http://127.0.0.1:" + upstream.httpPort()), "/api/tags");
    }

    private static List<CompletableFuture<Void>> register(boolean warmupEnabled, ClientFactory factory) {
        List<MicroserviceRoute> routes = List.of(llmRoute());
        ServerBuilder sb = Server.builder().http(0);
        Map<String, RouteCircuitBreaker> cbs = Map.of("llm", new RouteCircuitBreaker(3, 5000));
        return MicroserviceGatewayServer.registerLlmRoutes(
                sb, routes, factory, Duration.ofSeconds(5), cbs,
                LlmTokenRateLimiter.disabled(), LlmResponseCache.disabled(),
                1000, 30_000, GatewayAuthenticator.disabled(), warmupEnabled);
    }

    @Test
    void registerLlmRoutes_warmupEnabled_hitsHealthPath() {
        hits.clear();
        ClientFactory factory = ClientFactory.ofDefault();
        List<CompletableFuture<Void>> warmups = register(true, factory);

        CompletableFuture.allOf(warmups.toArray(new CompletableFuture[0])).join();

        assertThat(warmups).hasSize(1);
        assertThat(hits).containsExactly("/api/tags");
    }

    @Test
    void registerLlmRoutes_warmupDisabled_noUpstreamHit() {
        hits.clear();
        ClientFactory factory = ClientFactory.ofDefault();
        List<CompletableFuture<Void>> warmups = register(false, factory);

        assertThat(warmups).isEmpty();
        assertThat(hits).isEmpty();
    }

    @Test
    void buildLlmClientFactory_returnsUsableFactory() {
        ClientFactory factory = MicroserviceGatewayServer.buildLlmClientFactory(k -> null);
        try {
            assertThat(factory).isNotNull();
        } finally {
            factory.close();
        }
    }
}
