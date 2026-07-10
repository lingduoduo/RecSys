package com.recsys.application.gateway;

import com.recsys.config.EnvVars;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds one Armeria {@link EndpointGroup} per unique {@code (protocol, host, port, healthPath)} backend
 * and a {@link WebClient} per route over the shared group. When health checking is enabled each group is a
 * {@link HealthCheckedEndpointGroup} over a {@link DnsAddressEndpointGroup}, so a down upstream is dropped
 * from selection and requests fast-fail instead of hanging. The groups own background DNS-refresh and
 * health-check schedulers and must be released via {@link #close()}.
 *
 * <p>The default route table collapses onto ~3 backend authorities, so deduplication keeps the number of
 * background pollers proportional to backends, not routes.
 */
final class UpstreamEndpointGroups implements java.io.Closeable {

    record HealthCheckConfig(boolean healthCheckEnabled, long healthCheckIntervalMs, int dnsTtlMaxSeconds) {
        static HealthCheckConfig fromEnvironment() {
            boolean enabled = EnvVars.readBool("GATEWAY_UPSTREAM_HEALTHCHECK_ENABLED", true);
            long intervalMs = EnvVars.readLong("GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS", 10_000L);
            int dnsTtlMax = EnvVars.readInt("GATEWAY_UPSTREAM_DNS_TTL_MAX_S", 30);
            return new HealthCheckConfig(enabled, intervalMs, dnsTtlMax);
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
        return new UpstreamEndpointGroups(Map.copyOf(clients), List.copyOf(owned));
    }

    private static EndpointGroup buildGroup(SessionProtocol protocol, String host, int port,
                                            String healthPath, Duration responseTimeout,
                                            HealthCheckConfig config) {
        // DNS refresh bounded by the record TTL (upper bound = configured max). A selection timeout equal
        // to the response budget means an empty group never blocks longer than a normal request would.
        DnsAddressEndpointGroup dns = DnsAddressEndpointGroup.builder(host)
                .port(port)
                .ttl(1, Math.max(1, config.dnsTtlMaxSeconds()))
                .selectionTimeout(responseTimeout)
                .build();
        if (!config.healthCheckEnabled()) {
            return dns;
        }
        return HealthCheckedEndpointGroup.builder(dns, healthPath)
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
