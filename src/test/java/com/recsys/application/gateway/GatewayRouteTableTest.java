package com.recsys.application.gateway;
import com.recsys.application.gateway.MicroserviceRoute;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteTableTest {

    private final List<MicroserviceRoute> routes = MicroserviceRoute.defaults();

    @Test
    void canonicalStrategiesMapToProductionRoutes() {
        assertThat(RecommendationGatewayService.STRATEGY_ROUTES).containsExactlyInAnyOrderEntriesOf(
                Map.of("embedding", "embed-recall",
                        "model", "model-inference",
                        "online", "online-blend",
                        "sequential", "sequential"));
        assertThat(routes.stream().map(MicroserviceRoute::name))
                .contains("embed-recall", "model-inference", "online-blend", "sequential");
    }

    @Test
    void noDuplicatePrefixes() {
        List<String> prefixes = routes.stream()
                .map(MicroserviceRoute::prefix).toList();
        Set<String> unique = Set.copyOf(prefixes);
        assertThat(prefixes).hasSameSizeAs(unique);
    }

    @Test
    void deadRoutesAreRemoved() {
        Set<String> prefixes = routes.stream()
                .map(MicroserviceRoute::prefix)
                .collect(Collectors.toSet());
        assertThat(prefixes).doesNotContain(
                "/api/retrieval",
                "/api/ranking",
                "/api/agents",
                "/api/observability");
    }

    @Test
    void newProductionRoutesExist() {
        Set<String> prefixes = routes.stream()
                .map(MicroserviceRoute::prefix)
                .collect(Collectors.toSet());
        assertThat(prefixes).contains(
                "/api/recommend/embedding",
                "/api/recommend/model",
                "/api/recommend/online",
                "/api/recommend/sequential",
                "/api/knowledge");
    }

    @Test
    void backwardCompatRoutesAreKept() {
        Set<String> prefixes = routes.stream()
                .map(MicroserviceRoute::prefix)
                .collect(Collectors.toSet());
        assertThat(prefixes).contains(
                "/api/catalog",
                "/api/model",
                "/api/online",
                "/api/users",
                "/api/movies",
                "/api/features");
    }

    @Test
    void recommendPrefixRoutesRewriteToCorrectBackends() {
        MicroserviceRoute embedding = routes.stream()
                .filter(r -> r.prefix().equals("/api/recommend/embedding"))
                .findFirst().orElseThrow();
        assertThat(embedding.rewrite("/api/recommend/embedding/v2/recommend", null).getPath())
                .isEqualTo("/v2/recommend");
        assertThat(embedding.baseUri().getPort()).isEqualTo(6010);

        MicroserviceRoute model = routes.stream()
                .filter(r -> r.prefix().equals("/api/recommend/model"))
                .findFirst().orElseThrow();
        assertThat(model.rewrite("/api/recommend/model/v2/recommend", null).getPath())
                .isEqualTo("/v2/recommend");
        assertThat(model.baseUri().getPort()).isEqualTo(8080);

        MicroserviceRoute online = routes.stream()
                .filter(r -> r.prefix().equals("/api/recommend/online"))
                .findFirst().orElseThrow();
        assertThat(online.rewrite("/api/recommend/online/v2/recommend", null).getPath())
                .isEqualTo("/v2/recommend");
        assertThat(online.baseUri().getPort()).isEqualTo(7010);
    }
}
