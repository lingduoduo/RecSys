package com.recsys.application.gateway;
import com.recsys.reliability.RouteCircuitBreaker;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.serving.BaseApiService;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class GatewayHealthService extends BaseApiService {

    private final List<MicroserviceRoute> routes;
    private final Map<String, RouteCircuitBreaker> circuitBreakers;
    // Per-route clients built from each route's base URI so we can call the health path directly.
    private final Map<String, WebClient> healthClients;

    public GatewayHealthService(List<MicroserviceRoute> routes,
                         Duration timeout,
                         Map<String, RouteCircuitBreaker> circuitBreakers) {
        this.routes = List.copyOf(routes);
        this.circuitBreakers = Map.copyOf(circuitBreakers);
        // Build one WebClient per route base URI. responseTimeout is set slightly above the
        // health-check timeout so Armeria's own timeout fires after our deadline.
        this.healthClients = routes.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        MicroserviceRoute::name,
                        r -> WebClient.builder(r.baseUri().toString())
                                .responseTimeoutMillis(timeout.toMillis() + 500)
                                .build()));
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        // Fire all health checks in parallel so total latency = max(individual), not sum.
        // Critical for Cloud Map, which deregisters instances that miss successive health checks.
        List<CompletableFuture<ServiceHealth>> futures = routes.stream()
                .map(this::checkRoute)
                .toList();

        return HttpResponse.of(
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> {
                            Map<String, Object> services = new LinkedHashMap<>();
                            boolean allUp = true;
                            for (int i = 0; i < routes.size(); i++) {
                                MicroserviceRoute route = routes.get(i);
                                ServiceHealth health = futures.get(i).join();
                                RouteCircuitBreaker cb = circuitBreakers.get(route.name());
                                services.put(route.name(), health.asMap(route, cb));
                                allUp = allUp && health.up();
                            }
                            try {
                                byte[] body = MAPPER.writeValueAsBytes(Map.of(
                                        "status", allUp ? "UP" : "DEGRADED",
                                        "checkedAt", Instant.now().toString(),
                                        "services", services));
                                return HttpResponse.of(
                                        allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE,
                                        MediaType.JSON_UTF_8, body);
                            } catch (Exception e) {
                                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                            }
                        }));
    }

    private CompletableFuture<ServiceHealth> checkRoute(MicroserviceRoute route) {
        // healthUri() returns the full URI (scheme + host + health path). Strip the base URI
        // prefix to get only the path portion that the per-route WebClient should request.
        String healthPath = route.healthUri().getRawPath();
        String healthQuery = route.healthUri().getRawQuery();
        String target = healthQuery != null ? healthPath + "?" + healthQuery : healthPath;

        long startMs = System.currentTimeMillis();
        WebClient client = healthClients.get(route.name());
        return client.get(target).aggregate()
                .thenApply(agg -> new ServiceHealth(
                        agg.status().isSuccess(),
                        agg.status().code(),
                        System.currentTimeMillis() - startMs,
                        null))
                .exceptionally(t -> new ServiceHealth(
                        false, 0,
                        System.currentTimeMillis() - startMs,
                        t.getMessage()))
                .toCompletableFuture();
    }

    private record ServiceHealth(boolean up, int statusCode, long latencyMs, String error) {
        Map<String, Object> asMap(MicroserviceRoute route, RouteCircuitBreaker cb) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", up ? "UP" : "DOWN");
            m.put("prefix", route.prefix());
            m.put("baseUrl", route.baseUri().toString());
            m.put("healthUrl", route.healthUri().toString());
            m.put("statusCode", statusCode);
            m.put("latencyMs", latencyMs);
            if (cb != null) m.put("circuitState", cb.state().name());
            if (error != null && !error.isBlank()) m.put("error", error);
            return m;
        }
    }
}
