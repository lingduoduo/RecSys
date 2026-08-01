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

import static java.util.Map.entry;

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

    /** The port each target is actually scraped on (see docs/system_design/21_Observability.md §3.1). */
    private static final Map<String, Integer> SCRAPE_PORTS = Map.ofEntries(
            entry("recsys-online-serving", 7010),
            entry("recsys-api-gateway", 8010),
            entry("recsys-catalog-serving", 6010),
            entry("recsys-model-serving", 8080));

    private static final String PROMETHEUS_NAMESPACE_LABEL_VALUE = "monitoring";
    private static final String PROMETHEUS_POD_LABEL_VALUE = "prometheus";

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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
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

    /**
     * Structural (not substring) check that a NetworkPolicy admits Prometheus on the exact port
     * a service is actually scraped on. A prior version of this test did
     * {@code assertThat(yamlDump).contains("app.kubernetes.io/name: prometheus")}, which passes
     * for a rule on the wrong port, a rule with no namespace restriction (any namespace's
     * "prometheus"-labelled pod, not just monitoring's), or the same selector fragment sitting
     * under {@code egress} instead of {@code ingress} entirely — all reproduced and confirmed
     * to slip past the old assertion while writing this version. The fix asserts there is an
     * {@code ingress[]} rule whose {@code from[]} contains a single element carrying BOTH the
     * monitoring namespaceSelector AND the prometheus podSelector (same list element, i.e.
     * logical AND — two separate elements would be OR: "monitoring namespace" OR "anything
     * named prometheus"), and whose {@code ports[]} includes the service's real scrape port.
     */
    @Test
    void everyScrapedServiceAdmitsPrometheusIngress() throws IOException {
        List<Map<String, Object>> policies = ofKind(allBaseDocuments(), "NetworkPolicy");

        for (String target : EXPECTED_SCRAPE_TARGETS) {
            int scrapePort = SCRAPE_PORTS.get(target);
            Map<String, Object> policy = policies.stream()
                    .filter(p -> target.equals(nameOf(p)))
                    .findFirst()
                    .orElse(null);

            // A missing NetworkPolicy is a distinct failure from one that omits Prometheus:
            // Kubernetes admits all ingress by default when no policy targets a pod, so
            // Prometheus's scrape would technically get through — but so would everything
            // else. These four services are documented (CLAUDE.md, 21_Observability.md §3.2)
            // as deliberately ingress-restricted, so a policy silently disappearing is itself
            // a regression this test should catch, not wave through as "nothing to restrict".
            assertThat(policy)
                    .as("expected a NetworkPolicy named %s restricting ingress to this scraped "
                            + "service; if it were absent, ingress would be unrestricted rather "
                            + "than Prometheus-only", target)
                    .isNotNull();

            List<Map<String, Object>> ingressRules = listOf(mapAt(policy, "spec"), "ingress");
            boolean admitsPrometheus = ingressRules.stream().anyMatch(rule -> {
                List<Map<String, Object>> fromSources = listOf(rule, "from");
                List<Map<String, Object>> ports = listOf(rule, "ports");

                boolean fromPrometheus = fromSources.stream().anyMatch(source -> {
                    Map<String, Object> nsLabels = mapAt(source, "namespaceSelector", "matchLabels");
                    Map<String, Object> podLabels = mapAt(source, "podSelector", "matchLabels");
                    return nsLabels != null
                            && PROMETHEUS_NAMESPACE_LABEL_VALUE.equals(nsLabels.get("kubernetes.io/metadata.name"))
                            && podLabels != null
                            && PROMETHEUS_POD_LABEL_VALUE.equals(podLabels.get("app.kubernetes.io/name"));
                });

                boolean admitsScrapePort = ports.stream()
                        .anyMatch(p -> Integer.valueOf(scrapePort).equals(p.get("port")));

                return fromPrometheus && admitsScrapePort;
            });

            assertThat(admitsPrometheus)
                    .as("NetworkPolicy %s must have an ingress rule whose `from` admits a pod "
                            + "carrying BOTH the monitoring namespaceSelector and the prometheus "
                            + "podSelector (in the same list element) AND whose `ports` includes "
                            + "%d, the port this service is actually scraped on. Without all of "
                            + "that, its ServiceMonitor would be created and still scrape nothing",
                            target, scrapePort)
                    .isTrue();
        }
    }
}
