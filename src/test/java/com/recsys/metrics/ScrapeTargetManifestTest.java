package com.recsys.metrics;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A metric that is exposed but never collected is indistinguishable from a metric that was
 * never written. This asserts the three independent layers that must ALL line up before a
 * Prometheus scrape actually happens — each of which fails silently on its own:
 *
 * <ol>
 *   <li>a {@code ServiceMonitor} exists for the service;</li>
 *   <li>its {@code spec.selector.matchLabels} matches the <em>Service's own labels</em> —
 *       not the Service's pod selector, which is the easy thing to confuse it with;</li>
 *   <li>the service's {@code NetworkPolicy} admits ingress from Prometheus.</li>
 * </ol>
 *
 * <p>Before this test, online-serving and api-gateway failed all three: they published
 * Prometheus exposition that nothing in the cluster could have collected.
 */
class ScrapeTargetManifestTest {

    /** Every service that serves Prometheus exposition and therefore must be scraped. */
    private static final Set<String> EXPECTED_SCRAPE_TARGETS = Set.of(
            "recsys-model-serving",
            "recsys-catalog-serving",
            "recsys-online-serving",
            "recsys-api-gateway");

    private static final Path BASE = Path.of("k8s", "base");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documentsOf(String fileName) throws IOException {
        List<Map<String, Object>> docs = new ArrayList<>();
        try (InputStream in = Files.newInputStream(BASE.resolve(fileName))) {
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map<?, ?> map) docs.add((Map<String, Object>) map);
            }
        }
        return docs;
    }

    private static List<Map<String, Object>> allBaseDocuments() throws IOException {
        List<Map<String, Object>> all = new ArrayList<>();
        try (var files = Files.list(BASE)) {
            List<Path> yamls = files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
            for (Path p : yamls) all.addAll(documentsOf(p.getFileName().toString()));
        }
        return all;
    }

    private static List<Map<String, Object>> ofKind(
            List<Map<String, Object>> docs, String kind) {
        return docs.stream().filter(d -> kind.equals(d.get("kind"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> doc, String... path) {
        Map<String, Object> cursor = doc;
        for (String key : path) {
            Object next = cursor == null ? null : cursor.get(key);
            cursor = next instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }
        return cursor;
    }

    private static String nameOf(Map<String, Object> doc) {
        Map<String, Object> metadata = mapAt(doc, "metadata");
        return metadata == null ? null : String.valueOf(metadata.get("name"));
    }

    @Test
    void everyExposedServiceHasAServiceMonitor() throws IOException {
        List<Map<String, Object>> docs = allBaseDocuments();

        Set<String> monitored = ofKind(docs, "ServiceMonitor").stream()
                .map(ScrapeTargetManifestTest::nameOf)
                .collect(Collectors.toSet());

        assertThat(monitored)
                .as("every service exposing Prometheus metrics must have a ServiceMonitor, "
                        + "otherwise its metrics are published to nobody")
                .containsAll(EXPECTED_SCRAPE_TARGETS);
    }

    @Test
    void everyServiceMonitorSelectorMatchesRealServiceLabels() throws IOException {
        List<Map<String, Object>> docs = allBaseDocuments();

        List<Map<String, Object>> services = ofKind(docs, "Service");

        for (Map<String, Object> monitor : ofKind(docs, "ServiceMonitor")) {
            Map<String, Object> selector = mapAt(monitor, "spec", "selector", "matchLabels");
            assertThat(selector)
                    .as("ServiceMonitor %s has no spec.selector.matchLabels", nameOf(monitor))
                    .isNotNull();

            boolean matched = services.stream().anyMatch(service -> {
                Map<String, Object> labels = mapAt(service, "metadata", "labels");
                return labels != null && labels.entrySet().containsAll(selector.entrySet());
            });

            assertThat(matched)
                    .as("ServiceMonitor %s selects %s, but no Service carries those "
                            + "metadata.labels. Note the selector matches the SERVICE's labels, "
                            + "not the Service's pod selector — a monitor that matches nothing "
                            + "collects nothing, silently.", nameOf(monitor), selector)
                    .isTrue();
        }
    }

    @Test
    void everyScrapedServiceAdmitsPrometheusIngress() throws IOException {
        List<Map<String, Object>> policies = ofKind(allBaseDocuments(), "NetworkPolicy");

        for (String target : EXPECTED_SCRAPE_TARGETS) {
            Map<String, Object> policy = policies.stream()
                    .filter(p -> target.equals(nameOf(p)))
                    .findFirst()
                    .orElse(null);
            if (policy == null) {
                continue; // No policy means no restriction; nothing to assert.
            }
            String rendered = new Yaml().dump(policy);
            assertThat(rendered)
                    .as("NetworkPolicy %s restricts ingress but does not admit Prometheus, so "
                            + "its ServiceMonitor would be created and still scrape nothing",
                            target)
                    .contains("app.kubernetes.io/name: prometheus");
        }
    }
}
