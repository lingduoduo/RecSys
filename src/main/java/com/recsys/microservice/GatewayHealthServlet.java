package com.recsys.microservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class GatewayHealthServlet extends HttpServlet {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<MicroserviceRoute> routes;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final Map<String, RouteCircuitBreaker> circuitBreakers;

    GatewayHealthServlet(List<MicroserviceRoute> routes,
                         HttpClient httpClient,
                         Duration timeout,
                         Map<String, RouteCircuitBreaker> circuitBreakers) {
        this.routes = List.copyOf(routes);
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.circuitBreakers = Map.copyOf(circuitBreakers);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Fire all health checks in parallel so total latency = max(individual), not sum.
        // Critical for Cloud Map, which deregisters instances that miss successive health checks.
        List<CompletableFuture<ServiceHealth>> futures = routes.stream()
                .map(route -> CompletableFuture.supplyAsync(() -> check(route.healthUri())))
                .toList();

        long waitMs = timeout.toMillis() + 500L;
        Map<String, Object> services = new LinkedHashMap<>();
        boolean allUp = true;
        for (int i = 0; i < routes.size(); i++) {
            MicroserviceRoute route = routes.get(i);
            ServiceHealth health;
            try {
                health = futures.get(i).get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                health = new ServiceHealth(false, 0, timeout.toMillis(), "health check timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                health = new ServiceHealth(false, 0, timeout.toMillis(), "interrupted");
            } catch (ExecutionException e) {
                health = new ServiceHealth(false, 0, timeout.toMillis(),
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
            RouteCircuitBreaker cb = circuitBreakers.get(route.name());
            services.put(route.name(), health.asMap(route, route.healthUri(), cb));
            allUp = allUp && health.up();
        }

        response.setStatus(allUp ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(), Map.of(
                "status", allUp ? "UP" : "DEGRADED",
                "checkedAt", Instant.now().toString(),
                "services", services
        ));
    }

    private ServiceHealth check(java.net.URI healthUri) {
        long startNs = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<Void> upstream = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
            return new ServiceHealth(upstream.statusCode() < 500, upstream.statusCode(), latencyMs, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
            return new ServiceHealth(false, 0, latencyMs, "interrupted");
        } catch (RuntimeException | IOException e) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
            return new ServiceHealth(false, 0, latencyMs, e.getMessage());
        }
    }

    private record ServiceHealth(boolean up, int statusCode, long latencyMs, String error) {
        Map<String, Object> asMap(MicroserviceRoute route, java.net.URI healthUri,
                                  RouteCircuitBreaker circuitBreaker) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", up ? "UP" : "DOWN");
            result.put("prefix", route.prefix());
            result.put("baseUrl", route.baseUri().toString());
            result.put("healthUrl", healthUri.toString());
            result.put("statusCode", statusCode);
            result.put("latencyMs", latencyMs);
            if (circuitBreaker != null) {
                result.put("circuitState", circuitBreaker.state().name());
            }
            if (error != null && !error.isBlank()) {
                result.put("error", error);
            }
            return result;
        }
    }
}
