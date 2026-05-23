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
        Map<String, Object> services = new LinkedHashMap<>();
        boolean allUp = true;

        for (MicroserviceRoute route : routes) {
            java.net.URI healthUri = route.healthUri();
            ServiceHealth health = check(healthUri);
            services.put(route.name(), health.asMap(route, healthUri));
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
