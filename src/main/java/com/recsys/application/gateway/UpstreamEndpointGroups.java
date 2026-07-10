package com.recsys.application.gateway;

import com.recsys.config.EnvVars;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Builds one Armeria {@link EndpointGroup} per unique {@code (protocol, host, port, healthPath)} backend
 * and a {@link WebClient} per route over the shared group. When health checking is enabled each group is a
 * {@link HealthCheckedEndpointGroup} over a static {@link Endpoint}, so a down upstream is dropped from
 * selection and requests fast-fail instead of hanging. Host resolution stays with Armeria's default
 * per-connection resolver (unchanged from the previous plain-{@code WebClient} behavior, honoring the
 * 30 s Cloud Map DNS cache); the health check only decides whether that endpoint is currently selectable.
 * The health-checked groups own a background probe scheduler and must be released via {@link #close()}.
 *
 * <p>The default route table collapses onto ~3 backend authorities, so deduplication keeps the number of
 * background pollers proportional to backends, not routes.
 */
final class UpstreamEndpointGroups implements java.io.Closeable {

    record HealthCheckConfig(boolean healthCheckEnabled, long healthCheckIntervalMs) {
        static HealthCheckConfig fromEnvironment() {
            boolean enabled = EnvVars.readBool("GATEWAY_UPSTREAM_HEALTHCHECK_ENABLED", true);
            long intervalMs = EnvVars.readLong("GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS", 10_000L);
            return new HealthCheckConfig(enabled, intervalMs);
        }
    }

    private final Map<String, WebClient> clientsByRoute;
    private final List<EndpointGroup> ownedGroups;
    private volatile boolean closed;

    private UpstreamEndpointGroups(Map<String, WebClient> clientsByRoute, List<EndpointGroup> ownedGroups) {
        this.clientsByRoute = clientsByRoute;
        this.ownedGroups = ownedGroups;
    }

    static UpstreamEndpointGroups create(List<MicroserviceRoute> routes,
                                         Duration responseTimeout,
                                         Function<? super HttpClient, ? extends HttpClient> decorator,
                                         HealthCheckConfig config) {
        Map<String, EndpointGroup> groupsByKey = new LinkedHashMap<>();
        List<EndpointGroup> owned = new ArrayList<>();
        Map<String, WebClient> clients = new HashMap<>();

        for (MicroserviceRoute route : routes) {
            URI baseUri = route.baseUri();
            SessionProtocol protocol = "https".equalsIgnoreCase(baseUri.getScheme())
                    ? SessionProtocol.HTTPS : SessionProtocol.HTTP;
            String host = baseUri.getHost();
            int port = baseUri.getPort() != -1 ? baseUri.getPort() : protocol.defaultPort();
            String healthPath = route.healthPath();
            String key = protocol.uriText() + "://" + host + ":" + port + healthPath;

            EndpointGroup group = groupsByKey.computeIfAbsent(key, k -> {
                EndpointGroup built = buildGroup(protocol, host, port, healthPath, responseTimeout, config);
                owned.add(built);
                return built;
            });

            WebClientBuilder wcb = WebClient.builder(protocol, group)
                    .responseTimeoutMillis(responseTimeout.toMillis());
            if (decorator != null) {
                wcb.decorator(decorator);
            }
            clients.put(route.name(), wcb.build());
        }
        awaitInitialReadiness(owned, responseTimeout);
        return new UpstreamEndpointGroups(Map.copyOf(clients), List.copyOf(owned));
    }

    /**
     * Best-effort wait for each group's first endpoint resolution (DNS + first health check) so an
     * already-up upstream is selectable on the very first request instead of racing a cold group.
     * Bounded by the response budget and never fatal — an upstream still not ready stays empty and
     * fast-fails until it resolves, and startup never blocks indefinitely.
     */
    private static void awaitInitialReadiness(List<EndpointGroup> groups, Duration timeout) {
        if (groups.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] futures = groups.stream()
                .map(EndpointGroup::whenReady)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Some upstream did not become ready within the window — acceptable; it fast-fails until it does.
        }
    }

    private static EndpointGroup buildGroup(SessionProtocol protocol, String host, int port,
                                            String healthPath, Duration responseTimeout,
                                            HealthCheckConfig config) {
        // Static endpoint — Armeria's default per-connection resolver handles the host (literal IPs,
        // localhost, and DNS names alike), identical to the previous plain-WebClient behavior.
        Endpoint endpoint = Endpoint.of(host, port);
        if (!config.healthCheckEnabled()) {
            return endpoint;
        }
        // A selection timeout equal to the response budget means an empty (all-unhealthy) group never
        // blocks longer than a normal request would before fast-failing.
        return HealthCheckedEndpointGroup.builder(endpoint, healthPath)
                .protocol(protocol)
                .retryIntervalMillis(config.healthCheckIntervalMs())
                .selectionTimeoutMillis(responseTimeout.toMillis())
                .build();
    }

    WebClient clientFor(String routeName) {
        return clientsByRoute.get(routeName);
    }

    int groupCount() {
        return ownedGroups.size();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (EndpointGroup group : ownedGroups) {
            try {
                group.close();
            } catch (RuntimeException ignored) {
                // best-effort release; shutdown must not fail on a group already closing
            }
        }
    }
}
