package com.recsys.application.gateway;

import com.recsys.infrastructure.registry.ServiceRegistryProvider;
import com.recsys.infrastructure.registry.ServiceRegistryStore;
import com.recsys.resilience.RouteCircuitBreaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class GatewayHealthServiceRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MicroserviceRoute route(String name, String service) {
        return new MicroserviceRoute(name, "/api/" + name, name.toUpperCase() + "_URL",
                URI.create("http://127.0.0.1:1"), "/health", service);
    }

    private static JsonNode healthBody(GatewayHealthService svc) throws Exception {
        ServiceRequestContext ctx = ServiceRequestContext.builder(
                HttpRequest.of(HttpMethod.GET, "/health")).build();
        AggregatedHttpResponse resp = svc.serve(ctx, ctx.request()).aggregate().join();
        return MAPPER.readTree(resp.contentUtf8());
    }

    @Test
    void includesRegistrySectionWhenProviderPresent() throws Exception {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection())).thenReturn(Map.of("svc-a", "http://registered:6010"));
        ServiceRegistryProvider provider = new ServiceRegistryProvider(store, List.of("svc-a", "svc-b"), 0L, null);
        provider.refresh();

        List<MicroserviceRoute> routes = List.of(route("a", "svc-a"), route("b", "svc-b"));
        Map<String, RouteCircuitBreaker> cbs = Map.of("a", new RouteCircuitBreaker(), "b", new RouteCircuitBreaker());
        GatewayHealthService svc = new GatewayHealthService(routes, Duration.ofMillis(200), cbs, 8010, provider);

        JsonNode registry = healthBody(svc).get("registry");
        assertThat(registry).isNotNull();
        assertThat(registry.get("enabled").asBoolean()).isTrue();
        assertThat(registry.get("services").get("svc-a").get("source").asText()).isEqualTo("registry");
        assertThat(registry.get("services").get("svc-a").get("address").asText()).isEqualTo("http://registered:6010");
        assertThat(registry.get("services").get("svc-b").get("source").asText()).isEqualTo("static");
    }

    @Test
    void omitsRegistrySectionWhenNoProvider() throws Exception {
        List<MicroserviceRoute> routes = List.of(route("a", "svc-a"));
        Map<String, RouteCircuitBreaker> cbs = Map.of("a", new RouteCircuitBreaker());
        GatewayHealthService svc = new GatewayHealthService(routes, Duration.ofMillis(200), cbs, 8010);

        assertThat(healthBody(svc).has("registry")).isFalse();
    }
}
