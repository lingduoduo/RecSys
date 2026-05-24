package com.recsys.microservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class GatewayHealthServlet extends HttpServlet {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<MicroserviceRoute> routes;
    private final HttpClient httpClient;
    private final Duration timeout;

    GatewayHealthServlet(List<MicroserviceRoute> routes,
                         Duration timeout) {
        this.routes = List.copyOf(routes);
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        record RouteCheck(MicroserviceRoute route, URI healthUri,
                          long startNs, CompletableFuture<HttpResponse<Void>> future) {}

        List<RouteCheck> checks = routes.stream().map(route -> {
            URI healthUri = route.healthUri();
            HttpRequest req = HttpRequest.newBuilder(healthUri).timeout(timeout).GET().build();
            return new RouteCheck(route, healthUri, System.nanoTime(),
                    httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding()));
        }).toList();

        CompletableFuture.allOf(checks.stream().map(RouteCheck::future).toArray(CompletableFuture[]::new)).join();

        Map<String, Object> services = new LinkedHashMap<>();
        boolean allUp = true;
        for (RouteCheck check : checks) {
            long latencyMs = (System.nanoTime() - check.startNs()) / 1_000_000;
            ServiceHealth health;
            try {
                HttpResponse<Void> resp = check.future().join();
                health = new ServiceHealth(resp.statusCode() < 500, resp.statusCode(), latencyMs, null);
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                health = new ServiceHealth(false, 0, latencyMs, cause.getMessage());
            }
            services.put(check.route().name(), health.asMap(check.route(), check.healthUri()));
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

    private record ServiceHealth(boolean up, int statusCode, long latencyMs, String error) {
        Map<String, Object> asMap(MicroserviceRoute route, java.net.URI healthUri) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", up ? "UP" : "DOWN");
            result.put("prefix", route.prefix());
            result.put("baseUrl", route.baseUri().toString());
            result.put("healthUrl", healthUri.toString());
            result.put("statusCode", statusCode);
            result.put("latencyMs", latencyMs);
            if (error != null && !error.isBlank()) {
                result.put("error", error);
            }
            return result;
        }
    }
}
