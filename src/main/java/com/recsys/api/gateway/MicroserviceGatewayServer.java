package com.recsys.api.gateway;
import com.recsys.application.gateway.GatewayProxyService;
import com.recsys.application.gateway.GatewayRequestForwarder;
import com.recsys.application.gateway.LlmProxyService;
import com.recsys.application.gateway.GatewayHealthService;
import com.recsys.application.gateway.GatewayAuthenticator;
import com.recsys.application.gateway.MicroserviceRoute;
import com.recsys.application.gateway.RecommendationGatewayService;
import com.recsys.config.EnvVars;
import com.recsys.ratelimit.LlmTokenRateLimiter;
import com.recsys.ratelimit.GatewayRateLimiter;
import com.recsys.resilience.RouteCircuitBreaker;
import com.recsys.infrastructure.cache.LlmResponseCache;
import com.recsys.loadshed.GracefulServers;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class MicroserviceGatewayServer {
    private static final Logger log = LoggerFactory.getLogger(MicroserviceGatewayServer.class);
    private static final int DEFAULT_PORT = 8010;
    private static final Set<String> LLM_ROUTE_NAMES = Set.of("llm", "llm-explanation");
    // Cloud Map DNS TTL is 15–30 s. Cap the JVM cache so Blue/Green endpoint changes propagate.
    private static final String CLOUD_MAP_DNS_TTL_SECONDS = "30";

    private MicroserviceGatewayServer() {}

    public static void main(String[] args) throws Exception {
        int port = EnvVars.readInt("GATEWAY_PORT", DEFAULT_PORT);
        int timeoutMs = EnvVars.readInt("GATEWAY_TIMEOUT_MS", 3000);
        Duration timeout = Duration.ofMillis(timeoutMs);
        List<MicroserviceRoute> allRoutes = MicroserviceRoute.defaults();

        // Respect Cloud Map DNS TTL. The JVM caches successful lookups indefinitely by default,
        // which prevents new Cloud Map endpoint registrations from being picked up during
        // blue/green deployments. Only set if the caller hasn't already configured it.
        if (java.security.Security.getProperty("networkaddress.cache.ttl") == null) {
            java.security.Security.setProperty("networkaddress.cache.ttl", CLOUD_MAP_DNS_TTL_SECONDS);
        }

        // One circuit breaker per route — shared between proxy (records outcomes) and
        // health service (exposes state in the /health response body).
        int cbFailureThreshold = EnvVars.readInt("GATEWAY_CB_FAILURE_THRESHOLD", RouteCircuitBreaker.DEFAULT_FAILURE_THRESHOLD);
        long cbCooldownMs = EnvVars.readLong("GATEWAY_CB_COOLDOWN_MS", RouteCircuitBreaker.DEFAULT_COOLDOWN_MS);
        Map<String, RouteCircuitBreaker> circuitBreakers = allRoutes.stream()
                .collect(Collectors.toUnmodifiableMap(MicroserviceRoute::name,
                        r -> new RouteCircuitBreaker(cbFailureThreshold, cbCooldownMs)));

        // Split routes: LLM-backed routes get dedicated services with long timeouts and token budgets.
        List<MicroserviceRoute> llmRoutes = allRoutes.stream()
                .filter(r -> LLM_ROUTE_NAMES.contains(r.name()))
                .toList();
        List<MicroserviceRoute> proxyRoutes = allRoutes.stream()
                .filter(r -> !LLM_ROUTE_NAMES.contains(r.name()))
                .toList();

        GatewayRateLimiter rateLimiter = GatewayRateLimiter.fromEnvironment(proxyRoutes);
        GatewayAuthenticator authenticator = GatewayAuthenticator.fromEnvironment();
        GatewayRequestForwarder forwarder = new GatewayRequestForwarder(
                proxyRoutes, timeout, circuitBreakers, rateLimiter);
        RecommendationGatewayService recommendationService =
                new RecommendationGatewayService(proxyRoutes, forwarder, authenticator);

        // LLM requests can take much longer than regular API calls (large context, slow inference).
        // Use a separate timeout so LLM latency does not block the shared proxy pool.
        int llmTimeoutMs = EnvVars.readInt("LLM_TIMEOUT_MS", LlmProxyService.DEFAULT_TIMEOUT_MS);
        Duration llmTimeout = Duration.ofMillis(llmTimeoutMs);
        LlmTokenRateLimiter llmTokenRateLimiter = LlmTokenRateLimiter.fromEnvironment();
        LlmResponseCache llmResponseCache = LlmResponseCache.fromEnvironment();
        int llmDefaultTokenEstimate = EnvVars.readInt("LLM_DEFAULT_TOKEN_ESTIMATE", LlmProxyService.DEFAULT_TOKEN_ESTIMATE);
        long llmMaxRetryWaitMs = EnvVars.readLong("LLM_MAX_RETRY_WAIT_MS", LlmProxyService.DEFAULT_MAX_RETRY_WAIT_MS);

        ServerBuilder sb = Server.builder().http(port);

        // Health endpoint — exposes per-route circuit state and upstream reachability.
        sb.service("/health", new GatewayHealthService(allRoutes, timeout, circuitBreakers, port));

        // LLM path: build a tuned, shared ClientFactory (only when LLM routes exist) and register
        // each LLM route from it. Register LLM routes before the catch-all so Armeria's
        // more-specific prefix wins. Connections are established lazily on the first request.
        ClientFactory llmClientFactory = null;
        if (!llmRoutes.isEmpty()) {
            llmClientFactory = buildLlmClientFactory(System::getenv);
            registerLlmRoutes(sb, llmRoutes, llmClientFactory, llmTimeout, circuitBreakers,
                    llmTokenRateLimiter, llmResponseCache, llmDefaultTokenEstimate, llmMaxRetryWaitMs,
                    authenticator);
        }

        // Canonical recommendation endpoint — exact path takes precedence over the catch-all.
        sb.service("/api/recommend", recommendationService);

        // Catch-all proxy — handles all non-LLM routes using the same forwarding pipeline.
        sb.service("prefix:/",
                new GatewayProxyService(proxyRoutes, forwarder, authenticator));

        GracefulServers.applyShutdownWindow(sb);

        Server server = sb.build();

        final ClientFactory llmFactoryToClose = llmClientFactory;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down API gateway...");
            server.stop().join();
            if (llmFactoryToClose != null) {
                llmFactoryToClose.close();
            }
        }));

        log.info("Starting RecSys API gateway on port {}", port);
        log.info("Canonical recommendation routing is available at /api/recommend and defaults to model");
        for (MicroserviceRoute route : allRoutes) {
            log.info("Route {} {} -> {}", route.name(), route.prefix(), route.baseUri());
        }
        if (rateLimiter.isEnabled()) {
            log.info("Gateway local rate limiting enabled");
        }
        if (authenticator.isEnabled()) {
            log.info("Gateway API-key authentication enabled");
        }
        if (llmTokenRateLimiter.isEnabled()) {
            log.info("LLM token rate limiting enabled");
        }
        if (llmResponseCache.isEnabled()) {
            log.info("LLM response cache enabled");
        }

        server.start().join();
        server.blockUntilShutdown();
    }

    static ClientFactory buildLlmClientFactory(EnvVars.EnvReader env) {
        long connectMs = EnvVars.readLong(env, "LLM_CONNECT_TIMEOUT_MS", 2000L);
        long idleMs = EnvVars.readLong(env, "LLM_IDLE_TIMEOUT_MS", 60_000L);
        long pingMs = EnvVars.readLong(env, "LLM_PING_INTERVAL_MS", 20_000L);
        return ClientFactory.builder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .idleTimeout(Duration.ofMillis(idleMs))
                .pingIntervalMillis(pingMs)
                .build();
    }

    static void registerLlmRoutes(
            ServerBuilder sb,
            List<MicroserviceRoute> llmRoutes,
            ClientFactory llmClientFactory,
            Duration llmTimeout,
            Map<String, RouteCircuitBreaker> circuitBreakers,
            LlmTokenRateLimiter tokenRateLimiter,
            LlmResponseCache responseCache,
            int defaultTokenEstimate,
            long maxRetryWaitMs,
            GatewayAuthenticator authenticator) {
        for (MicroserviceRoute llmRoute : llmRoutes) {
            LlmProxyService llmProxyService = new LlmProxyService(
                    llmRoute,
                    llmTimeout,
                    circuitBreakers.get(llmRoute.name()),
                    tokenRateLimiter,
                    responseCache,
                    defaultTokenEstimate,
                    maxRetryWaitMs,
                    authenticator,
                    llmClientFactory);
            sb.service(
                    com.linecorp.armeria.server.Route.builder()
                            .pathPrefix(llmRoute.prefix() + "/")
                            .build(),
                    llmProxyService);
        }
    }
}
