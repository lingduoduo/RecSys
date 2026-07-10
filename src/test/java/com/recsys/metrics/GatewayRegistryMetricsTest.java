package com.recsys.metrics;

import com.recsys.infrastructure.registry.ServiceRegistryProvider;
import com.recsys.infrastructure.registry.ServiceRegistryStore;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class GatewayRegistryMetricsTest {

    private static ServiceRegistryProvider providerResolving(Map<String, String> map, List<String> known) {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection())).thenReturn(map);
        return new ServiceRegistryProvider(store, known, 0L, null);
    }

    @Test
    void registersRegistryGaugesAndCounters() {
        List<String> known = List.of("svc-a", "svc-b");
        ServiceRegistryProvider provider = providerResolving(Map.of("svc-a", "http://a:1"), known);
        provider.refresh(); // one success; svc-a resolves, svc-b does not
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        long fixedNow = 10_000L;

        GatewayRegistryMetrics.register(registry, provider, known, () -> fixedNow + provider.lastRefreshAtMs());

        assertThat(registry.get("gateway_registry_services_total").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("gateway_registry_services_resolved").gauge().value()).isEqualTo(1.0);
        // age = (lastRefreshAtMs + fixedNow) - lastRefreshAtMs = fixedNow ms = 10 s
        assertThat(registry.get("gateway_registry_snapshot_age_seconds").gauge().value()).isEqualTo(10.0);
        assertThat(registry.get("gateway_registry_refresh_total").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("gateway_registry_refresh_failures_total").functionCounter().count()).isEqualTo(0.0);
    }

    @Test
    void snapshotAgeIsMinusOneBeforeFirstRefresh() {
        List<String> known = List.of("svc-a");
        ServiceRegistryProvider provider = providerResolving(Map.of(), known); // never refreshed
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        GatewayRegistryMetrics.register(registry, provider, known, () -> 5_000L);

        assertThat(registry.get("gateway_registry_snapshot_age_seconds").gauge().value()).isEqualTo(-1.0);
        assertThat(registry.get("gateway_registry_services_resolved").gauge().value()).isEqualTo(0.0);
    }
}
