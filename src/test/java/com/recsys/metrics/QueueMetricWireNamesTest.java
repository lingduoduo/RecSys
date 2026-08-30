package com.recsys.metrics;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the residual risk left open by PR #296's review: nothing pinned that
 * {@link QueueMetrics#register} actually produces the four Prometheus series names
 * {@code RecsysQueueFillingUp} and {@code RecsysQueueRejecting} query, and nothing pinned that
 * those two alerts query names {@link QueueMetrics} actually emits. Both are the same failure
 * shape recorded in {@code docs/system_design/18_Fault_Tolerance.md} §8.2/§8.4: instrumentation
 * (or an alert) that looks present and is observable by nothing, "an alert on a metric that is
 * never emitted... looks like coverage and can never fire".
 *
 * <p>This branch has already been bitten by this exact shape twice — {@code MetricCollectingService}
 * emitting a Summary where a histogram was assumed, and a bulkhead rejection counter that could
 * never fire in production — so the transformation from Micrometer meter name to Prometheus wire
 * name is verified here against a real {@link PrometheusMeterRegistry}, not assumed from the
 * meter names in {@link QueueMetrics}'s source.
 *
 * <p><b>The crux is the {@code _total} suffix.</b> {@link QueueMetrics} registers a
 * {@code FunctionCounter} named {@code recsys.queue.rejected}; Micrometer's Prometheus client
 * appends {@code _total} to a counter on the wire. {@code RecsysQueueRejecting} in
 * {@code k8s/base/prometheus-rules.yaml} queries {@code recsys_queue_rejected_total} — the
 * suffixed name. A Micrometer upgrade that changes that naming convention (or a
 * {@code QueueMetrics} edit that renames the meter without updating the alert, or vice versa)
 * would previously merge green; this test scrapes a real registry and fails loudly instead.
 */
class QueueMetricWireNamesTest {

    private static final Path ALERT_RULES_FILE = Path.of("k8s", "base", "prometheus-rules.yaml");

    /** Matches a Prometheus series name of the shape this codebase's queue metrics use. */
    private static final Pattern QUEUE_METRIC_NAME = Pattern.compile("recsys_queue_[A-Za-z0-9_]+");

    /** Minimal Source whose values this test controls directly; capacity must stay positive. */
    private static final class FakeQueue implements QueueMetrics.Source {
        @Override public int depth() { return 3; }
        @Override public int capacity() { return 10; }
        @Override public long rejected(QueueMetrics.RejectionReason reason) { return 0; }
    }

    /**
     * Assertion 1: register a queue on a real Prometheus registry and scrape it, then assert the
     * exact wire-format series names a PromQL author (or an alert) would have to type exist,
     * including the {@code _total}-suffixed rejection counter and all three {@code reason} tag
     * values {@link QueueMetrics#register} always creates regardless of whether anything has been
     * rejected yet.
     */
    @Test
    void queueMetersAppearOnAPrometheusScrapeWithTheirWireNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        QueueMetrics.register(registry, "wire-name-check", new FakeQueue());

        String scraped = registry.scrape();

        assertThat(scraped)
                .as("recsys_queue_depth must be on the wire under the 'queue' tag; full scrape:\n%s", scraped)
                .contains("recsys_queue_depth{queue=\"wire-name-check\",} 3.0");
        assertThat(scraped)
                .as("recsys_queue_capacity must be on the wire under the 'queue' tag; full scrape:\n%s", scraped)
                .contains("recsys_queue_capacity{queue=\"wire-name-check\",} 10.0");
        assertThat(scraped)
                .as("recsys_queue_utilization must be on the wire under the 'queue' tag; full scrape:\n%s", scraped)
                .contains("recsys_queue_utilization{queue=\"wire-name-check\",} 0.3");

        for (String reason : List.of("full", "shutdown", "invalid_key")) {
            assertThat(scraped)
                    .as("recsys_queue_rejected_total with reason=\"%s\" must be on the wire — this is the "
                            + "_total suffix Micrometer's Prometheus client appends to a FunctionCounter "
                            + "named recsys.queue.rejected. RecsysQueueRejecting queries exactly this "
                            + "suffixed name; if this line is missing, either QueueMetrics stopped "
                            + "registering that reason, or a Micrometer upgrade changed the "
                            + "counter-naming convention (e.g. dropped or changed the _total suffix) — "
                            + "check registry.config().namingConvention() and the FunctionCounter "
                            + "registration in QueueMetrics.register before assuming the alert is wrong. "
                            + "Full scrape:\n%s",
                            reason, scraped)
                    .contains("recsys_queue_rejected_total{queue=\"wire-name-check\",reason=\"" + reason + "\",} 0.0");
        }
    }

    /**
     * Assertion 2: the alert file cannot reference a {@code recsys_queue_*} series the code never
     * emits. Extracts every such identifier from an {@code expr:} in
     * {@code k8s/base/prometheus-rules.yaml} — deriving the expected set from the file rather than
     * hardcoding it, so a future alert added against a misspelled or renamed queue metric fails
     * this test instead of sitting in Prometheus looking like coverage (§8.4's first trap).
     */
    @Test
    void everyQueueMetricNamedInAnAlertExpressionIsActuallyEmitted() throws IOException {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        QueueMetrics.register(registry, "wire-name-check", new FakeQueue());
        String scraped = registry.scrape();

        Set<String> namesInAlertExpressions = queueMetricNamesInAlertExpressions();

        assertThat(namesInAlertExpressions)
                .as("expected to find recsys_queue_* identifiers inside expr: fields of %s; if this is "
                        + "empty the extraction itself is broken, not the alerts", ALERT_RULES_FILE)
                .isNotEmpty();

        for (String name : namesInAlertExpressions) {
            assertThat(scraped)
                    .as("%s appears in an alert expr in %s but never appears on a real Prometheus scrape "
                            + "of QueueMetrics. Either QueueMetrics.register no longer emits a series by "
                            + "this name (renamed, removed, or a Micrometer naming-convention change "
                            + "altered the wire name — e.g. the _total suffix on the rejection counter), "
                            + "or the alert file names a metric that was misspelled or never wired up in "
                            + "the first place. Either way this is docs/system_design/18_Fault_Tolerance.md "
                            + "§8.4's first trap: an alert on a metric that is never emitted looks like "
                            + "coverage and can never fire. Full scrape:\n%s",
                            name, ALERT_RULES_FILE, scraped)
                    .contains(name);
        }
    }

    /**
     * Walks the parsed YAML tree collecting every string found under an {@code expr} key, then
     * extracts {@code recsys_queue_*} identifiers out of those strings only — deliberately not
     * out of {@code summary}/{@code description} annotations, which are prose for a human, not a
     * PromQL selector Prometheus actually evaluates.
     */
    private static Set<String> queueMetricNamesInAlertExpressions() throws IOException {
        List<Object> documents = new ArrayList<>();
        try (InputStream in = Files.newInputStream(ALERT_RULES_FILE)) {
            new Yaml().loadAll(in).forEach(documents::add);
        }

        List<String> exprValues = new ArrayList<>();
        for (Object doc : documents) {
            collectExprStrings(doc, exprValues);
        }

        Set<String> names = new LinkedHashSet<>();
        for (String expr : exprValues) {
            Matcher matcher = QUEUE_METRIC_NAME.matcher(expr);
            while (matcher.find()) {
                names.add(matcher.group());
            }
        }
        return names;
    }

    private static void collectExprStrings(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("expr".equals(entry.getKey()) && entry.getValue() instanceof String s) {
                    out.add(s);
                } else {
                    collectExprStrings(entry.getValue(), out);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectExprStrings(item, out);
            }
        }
    }
}
