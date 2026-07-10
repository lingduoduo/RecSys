package com.recsys.metrics;

import com.recsys.infrastructure.registry.ServiceRegistryProvider;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Collection;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Registers Prometheus meters for the gateway's service-registry consumption. All meters are
 * pull-based — they sample {@link ServiceRegistryProvider} state at scrape time, so no background
 * thread runs and no Redis call happens during a scrape.
 */
public final class GatewayRegistryMetrics {

    private GatewayRegistryMetrics() {}

    public static void register(MeterRegistry registry,
                                ServiceRegistryProvider provider,
                                Collection<String> serviceNames,
                                LongSupplier clockMs) {
        List<String> names = List.copyOf(serviceNames);

        Gauge.builder("gateway_registry_services_total", names, List::size)
                .description("Number of distinct backend services mapped to the registry")
                .register(registry);

        Gauge.builder("gateway_registry_services_resolved", provider,
                        p -> resolvedCount(p, names))
                .description("How many mapped services currently resolve from the registry (rest use static fallback)")
                .register(registry);

        Gauge.builder("gateway_registry_snapshot_age_seconds", provider,
                        p -> snapshotAgeSeconds(p, clockMs))
                .description("Seconds since the last successful registry refresh, or -1 if never refreshed")
                .baseUnit("s")
                .register(registry);

        FunctionCounter.builder("gateway_registry_refresh_total", provider,
                        p -> (double) p.refreshSuccessCount())
                .description("Successful registry refreshes since startup")
                .register(registry);

        FunctionCounter.builder("gateway_registry_refresh_failures_total", provider,
                        p -> (double) p.refreshFailureCount())
                .description("Failed (fail-static) registry refreshes since startup")
                .register(registry);
    }

    private static double resolvedCount(ServiceRegistryProvider provider, List<String> names) {
        long resolved = names.stream().filter(n -> provider.resolve(n).isPresent()).count();
        return (double) resolved;
    }

    private static double snapshotAgeSeconds(ServiceRegistryProvider provider, LongSupplier clockMs) {
        long last = provider.lastRefreshAtMs();
        if (last == 0L) {
            return -1.0;
        }
        return (clockMs.getAsLong() - last) / 1000.0;
    }
}
