package com.recsys.microservice;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class MicroserviceGatewayServer {
    private static final Logger log = LoggerFactory.getLogger(MicroserviceGatewayServer.class);
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 8010;
    // Cloud Map DNS TTL is 15–30 s. Cap the JVM cache so Blue/Green endpoint changes propagate.
    private static final String CLOUD_MAP_DNS_TTL_SECONDS = "30";

    private MicroserviceGatewayServer() {}

    public static void main(String[] args) throws Exception {
        int port = readIntEnv("GATEWAY_PORT", DEFAULT_PORT);
        int timeoutMs = readIntEnv("GATEWAY_TIMEOUT_MS", 3000);
        Duration timeout = Duration.ofMillis(timeoutMs);
        List<MicroserviceRoute> allRoutes = MicroserviceRoute.defaults();

        // Respect Cloud Map DNS TTL. The JVM caches successful lookups indefinitely by default,
        // which prevents new Cloud Map endpoint registrations from being picked up during
        // blue/green deployments. Only set if the caller hasn't already configured it.
        if (java.security.Security.getProperty("networkaddress.cache.ttl") == null) {
            java.security.Security.setProperty("networkaddress.cache.ttl", CLOUD_MAP_DNS_TTL_SECONDS);
        }

        // One shared HttpClient for both proxy and health-check servlets: single connection pool,
        // one thread group, consistent timeout behaviour.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // LLM requests can take much longer than regular API calls (large context, slow inference).
        // Use a separate HttpClient with a dedicated timeout so LLM latency does not block the
        // shared pool.
        int llmTimeoutMs = readIntEnv("LLM_TIMEOUT_MS", LlmProxyServlet.DEFAULT_TIMEOUT_MS);
        Duration llmTimeout = Duration.ofMillis(llmTimeoutMs);
        HttpClient llmHttpClient = HttpClient.newBuilder()
                .connectTimeout(llmTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // One circuit breaker per route — shared between proxy (records outcomes) and
        // health servlet (exposes state in the /health response body).
        int cbFailureThreshold = readIntEnv("GATEWAY_CB_FAILURE_THRESHOLD", RouteCircuitBreaker.DEFAULT_FAILURE_THRESHOLD);
        long cbCooldownMs = readLongEnv("GATEWAY_CB_COOLDOWN_MS", RouteCircuitBreaker.DEFAULT_COOLDOWN_MS);
        Map<String, RouteCircuitBreaker> circuitBreakers = allRoutes.stream()
                .collect(Collectors.toUnmodifiableMap(MicroserviceRoute::name,
                        r -> new RouteCircuitBreaker(cbFailureThreshold, cbCooldownMs)));

        // Split routes: LLM gets its own servlet; everything else uses the general proxy.
        MicroserviceRoute llmRoute = allRoutes.stream()
                .filter(r -> "llm".equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no llm route in defaults"));
        List<MicroserviceRoute> proxyRoutes = allRoutes.stream()
                .filter(r -> !"llm".equals(r.name()))
                .toList();

        GatewayRateLimiter rateLimiter = GatewayRateLimiter.fromEnvironment(proxyRoutes);

        LlmTokenRateLimiter llmTokenRateLimiter = LlmTokenRateLimiter.fromEnvironment();
        LlmResponseCache llmResponseCache = LlmResponseCache.fromEnvironment();
        int llmDefaultTokenEstimate = readIntEnv("LLM_DEFAULT_TOKEN_ESTIMATE", LlmProxyServlet.DEFAULT_TOKEN_ESTIMATE);
        long llmMaxRetryWaitMs = readLongEnv("LLM_MAX_RETRY_WAIT_MS", LlmProxyServlet.DEFAULT_MAX_RETRY_WAIT_MS);

        LlmProxyServlet llmProxyServlet = new LlmProxyServlet(
                llmRoute,
                llmHttpClient,
                llmTimeout,
                circuitBreakers.get("llm"),
                llmTokenRateLimiter,
                llmResponseCache,
                llmDefaultTokenEstimate,
                llmMaxRetryWaitMs
        );

        Server server = new Server(new InetSocketAddress(DEFAULT_HOST, port));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new GatewayHealthServlet(allRoutes, httpClient, timeout, circuitBreakers)), "/health");
        // Register LLM servlet before the catch-all so Jetty's more-specific path wins.
        context.addServlet(new ServletHolder(llmProxyServlet), "/api/llm/*");
        context.addServlet(new ServletHolder(new GatewayProxyServlet(proxyRoutes, httpClient, timeout, circuitBreakers, rateLimiter)), "/*");
        server.setHandler(context);
        server.setStopAtShutdown(true);

        log.info("Starting RecSys API gateway on port {}", port);
        for (MicroserviceRoute route : allRoutes) {
            log.info("Route {} {} -> {}", route.name(), route.prefix(), route.baseUri());
        }
        if (rateLimiter.isEnabled()) {
            log.info("Gateway local rate limiting enabled");
        }
        if (llmTokenRateLimiter.isEnabled()) {
            log.info("LLM token rate limiting enabled");
        }
        if (llmResponseCache.isEnabled()) {
            log.info("LLM response cache enabled (timeout={}ms)", llmTimeoutMs);
        }
        server.start();
        server.join();
    }

    private static int readIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid integer: " + value);
        }
    }

    private static long readLongEnv(String name, long defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid long: " + value);
        }
    }

}
