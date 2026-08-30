package com.recsys.metrics;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code prometheus-rules.test.yaml}'s promtool fixtures carry {@code job="..."} labels on their
 * input series and expected-alert labels. Those labels are meaningful only because Prometheus
 * Operator, absent a {@code jobLabel} override, sets the {@code job} label to the scraped
 * Service's own name — nothing in the promtool run itself checks that a fixture's {@code job}
 * matches a Service that actually exists and is actually scraped. A fixture testing
 * {@code job="recsys-typo-service"} would still evaluate the alert rule correctly (rules key off
 * {@code namespace="recsys"}, not the job value) and still pass promtool; only the page text
 * — {@code {{ $labels.job }}} in the alert annotation — would end up naming a service nobody
 * scraped.
 *
 * <p>This pins the two facts that make the fixtures' {@code job} values trustworthy:
 *
 * <ol>
 *   <li>every {@code recsys-}-prefixed {@code job} label in the fixtures names a real Service
 *       that some {@code ServiceMonitor} in {@code k8s/base} actually selects;</li>
 *   <li>no {@code ServiceMonitor} declares {@code jobLabel}. This is the assumption underneath
 *       assertion 1, not a restatement of it: if a future {@code ServiceMonitor} adds
 *       {@code jobLabel}, the Operator stops defaulting {@code job} to the Service name, every
 *       fixture's {@code job} value silently stops matching what Prometheus actually produces,
 *       and assertion 1 — which is defined in terms of Service names — would keep passing.</li>
 * </ol>
 *
 * <p>The fixtures also carry deliberately foreign job labels — {@code unrelated-service},
 * {@code no-namespace-service}, {@code unrelated-spring-service} — as the near-miss cases that
 * prove the alerts' {@code namespace="recsys"} scoping actually excludes other teams' series.
 * Both assertions here are scoped to the {@code recsys-} prefix precisely so those negative
 * fixtures stay untouched: they are not bugs to reconcile against real Services, they are the
 * point of the test suite they live in.
 */
class PromtoolJobLabelManifestTest {

    private static final Path BASE = Path.of("k8s", "base");
    private static final String FIXTURE_FILE = "prometheus-rules.test.yaml";

    /**
     * Only job labels under this prefix are checked against real Services — see the class
     * javadoc for why the foreign job labels in the fixtures must stay exempt rather than be
     * "fixed" to a real Service name.
     */
    private static final String RECSYS_JOB_PREFIX = "recsys-";

    /** Matches a PromQL-style {@code job="..."} label matcher inside an {@code input_series} string. */
    private static final Pattern SERIES_JOB_LABEL = Pattern.compile("job=\"([^\"]*)\"");

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

    /**
     * Walks the whole parsed fixture tree collecting every {@code job} label value, regardless
     * of whether it appears as a plain YAML mapping entry (promtool's {@code exp_labels.job}) or
     * embedded inside a PromQL-style series string (promtool's {@code input_series[].series}).
     * Walking rather than addressing fixed paths means a fixture reorganized to add another
     * place {@code job} can appear (a new {@code alert_rule_test} shape, an extra label block)
     * is still covered without touching this test.
     */
    private static void collectJobLabels(Object node, Set<String> jobLabels) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("job".equals(entry.getKey()) && entry.getValue() instanceof String value) {
                    jobLabels.add(value);
                }
                collectJobLabels(entry.getValue(), jobLabels);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) collectJobLabels(item, jobLabels);
        } else if (node instanceof String s) {
            Matcher matcher = SERIES_JOB_LABEL.matcher(s);
            while (matcher.find()) jobLabels.add(matcher.group(1));
        }
    }

    private static Set<String> recsysJobLabelsIn(List<Map<String, Object>> fixtureDocs) {
        Set<String> all = new LinkedHashSet<>();
        for (Map<String, Object> doc : fixtureDocs) collectJobLabels(doc, all);
        return all.stream()
                .filter(job -> job.startsWith(RECSYS_JOB_PREFIX))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Service names that some {@code ServiceMonitor} in {@code k8s/base} actually selects
     * (matched the same way {@code ScrapeTargetManifestTest} matches them: a Service whose own
     * {@code metadata.labels} carry every {@code spec.selector.matchLabels} entry — not the
     * Service's pod selector). This is the set the Operator would default {@code job} to for
     * each of those Services, since no monitor declares {@code jobLabel}.
     */
    private static Set<String> serviceMonitorSelectedServiceNames(List<Map<String, Object>> docs) {
        List<Map<String, Object>> services = ofKind(docs, "Service");
        Set<String> selected = new LinkedHashSet<>();
        for (Map<String, Object> monitor : ofKind(docs, "ServiceMonitor")) {
            Map<String, Object> selector = mapAt(monitor, "spec", "selector", "matchLabels");
            if (selector == null) continue;
            for (Map<String, Object> service : services) {
                Map<String, Object> labels = mapAt(service, "metadata", "labels");
                if (labels != null && labels.entrySet().containsAll(selector.entrySet())) {
                    selected.add(nameOf(service));
                }
            }
        }
        return selected;
    }

    @Test
    void everyRecsysFixtureJobLabelNamesAServiceMonitorScrapeTarget() throws IOException {
        List<Map<String, Object>> docs = allBaseDocuments();

        Set<String> fixtureJobLabels = recsysJobLabelsIn(documentsOf(FIXTURE_FILE));
        Set<String> realTargets = serviceMonitorSelectedServiceNames(docs);

        assertThat(fixtureJobLabels)
                .as("prometheus-rules.test.yaml uses a 'recsys-'-prefixed job label that names "
                        + "no Service any ServiceMonitor in k8s/base actually selects (real "
                        + "targets: %s). Either the fixture's job value drifted from a renamed "
                        + "Service/ServiceMonitor, or the fixture is testing a series Prometheus "
                        + "would never produce — either way the alert would still fire on "
                        + "namespace=\"recsys\" alone, but {{ $labels.job }} in the page text "
                        + "would name the wrong thing. Foreign job labels used as negative cases "
                        + "(unrelated-service, no-namespace-service, unrelated-spring-service) "
                        + "are exempt by design and must not be added here.", realTargets)
                .isSubsetOf(realTargets);
    }

    @Test
    void noServiceMonitorDeclaresAJobLabelOverride() throws IOException {
        List<Map<String, Object>> monitors = ofKind(allBaseDocuments(), "ServiceMonitor");

        for (Map<String, Object> monitor : monitors) {
            Map<String, Object> spec = mapAt(monitor, "spec");
            Object jobLabel = spec == null ? null : spec.get("jobLabel");

            assertThat(jobLabel)
                    .as("ServiceMonitor %s declares spec.jobLabel=%s. Prometheus Operator only "
                            + "defaults the 'job' label to the Service name when no jobLabel is "
                            + "set; every 'recsys-' job value in prometheus-rules.test.yaml "
                            + "assumes that default. Adding jobLabel here silently invalidates "
                            + "every fixture's job label without failing "
                            + "everyRecsysFixtureJobLabelNamesAServiceMonitorScrapeTarget, which "
                            + "only checks Service names, not how 'job' is actually derived.",
                            nameOf(monitor), jobLabel)
                    .isNull();
        }
    }
}
