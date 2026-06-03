package com.recsys.microservice;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class MicroserviceRouteTest {

    @Test
    void matchesLongestPrefix() {
        MicroserviceRoute catalog = new MicroserviceRoute(
                "catalog", "/api/catalog", "CATALOG_SERVICE_URL", URI.create("http://catalog:6010"), "/health");
        MicroserviceRoute catalogAdmin = new MicroserviceRoute(
                "catalog-admin", "/api/catalog/admin", "CATALOG_ADMIN_SERVICE_URL",
                URI.create("http://catalog-admin:6011"), "/health");

        MicroserviceRoute matched = MicroserviceRoute.match(
                List.of(catalog, catalogAdmin),
                "/api/catalog/admin/cache"
        );

        assertEquals(catalogAdmin, matched);
    }

    @Test
    void routeTableMatchesLongestPrefix() {
        MicroserviceRoute catalog = new MicroserviceRoute(
                "catalog", "/api/catalog", "CATALOG_SERVICE_URL", URI.create("http://catalog:6010"), "/health");
        MicroserviceRoute catalogAdmin = new MicroserviceRoute(
                "catalog-admin", "/api/catalog/admin", "CATALOG_ADMIN_SERVICE_URL",
                URI.create("http://catalog-admin:6011"), "/health");
        MicroserviceRouteTable table = new MicroserviceRouteTable(List.of(catalog, catalogAdmin));

        assertEquals(catalogAdmin, table.match("/api/catalog/admin/cache"));
        assertEquals(catalog, table.match("/api/catalog"));
        assertNull(table.match("/api/catalog-admin"));
    }

    @Test
    void doesNotMatchPartialPathSegments() {
        MicroserviceRoute route = new MicroserviceRoute(
                "model", "/api/model", "MODEL_SERVICE_URL", URI.create("http://model:8080"), "/health/ready");

        assertNull(MicroserviceRoute.match(List.of(route), "/api/modeling/recommend"));
    }

    @Test
    void rewritesGatewayPrefixAndPreservesQuery() {
        MicroserviceRoute route = new MicroserviceRoute(
                "online", "/api/online", "ONLINE_SERVICE_URL",
                URI.create("http://online:7010/base"), "/health");

        URI rewritten = route.rewrite("/api/online/online/recommendation", "userId=123&k=5");

        assertEquals("http://online:7010/base/online/recommendation?userId=123&k=5", rewritten.toString());
    }

    @Test
    void healthUri_combinesBaseUriAndHealthPath() {
        MicroserviceRoute route = new MicroserviceRoute(
                "model", "/api/model", "MODEL_SERVICE_URL",
                URI.create("http://model-serving.recsys.internal:8080"), "/health/ready");

        assertEquals("http://model-serving.recsys.internal:8080/health/ready",
                route.healthUri().toString());
    }

    @Test
    void healthUri_worksWithBaseUriSubPath() {
        MicroserviceRoute route = new MicroserviceRoute(
                "catalog", "/api/catalog", "CATALOG_SERVICE_URL",
                URI.create("http://catalog-serving.recsys.internal:6010/v1"), "/health");

        assertEquals("http://catalog-serving.recsys.internal:6010/v1/health",
                route.healthUri().toString());
    }

    @Test
    void defaultsExposeGatewayDomainServicesAndLegacyRoutes() {
        List<String> names = MicroserviceRoute.defaults().stream()
                .map(MicroserviceRoute::name)
                .toList();

        // LLM routes are opt-in (only registered when LLM_SERVICE_URL / LLM_EXPLANATION_SERVICE_URL
        // env vars are set), so they are not asserted here.
        assertTrue(names.containsAll(List.of(
                "user-profile",
                "movie-metadata",
                "feature",
                "recommendation-retrieval",
                "ranking",
                "agent-workflow",
                "observability",
                "catalog",
                "model",
                "online"
        )));
        assertFalse(names.contains("llm"),             "llm route must not appear without LLM_SERVICE_URL");
        assertFalse(names.contains("llm-explanation"), "llm-explanation must not appear without LLM_EXPLANATION_SERVICE_URL");
    }
}
