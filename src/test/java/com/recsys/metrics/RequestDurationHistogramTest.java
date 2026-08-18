package com.recsys.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one thing {@link RequestDurationHistogram} exists to fix: that a meter named
 * {@code *.request.duration} is exposed to Prometheus as a Histogram ({@code _bucket} series with
 * {@code le=} labels), not only as the client-side-percentile Summary Armeria's decorator produces
 * on its own. See {@code MetricCollectingService.newDecorator(...)} call sites in
 * {@code RecSysServer}, {@code MicroserviceGatewayServer} and {@code OnlinePredictionServer} — a
 * later alert queries {@code histogram_quantile(..., rate(..._bucket[5m]))} against exactly this
 * series, and against a Summary that query silently matches nothing.
 *
 * <p>Uses a real {@link PrometheusMeterRegistry} and scrapes it, rather than inspecting
 * {@code DistributionStatisticConfig} directly, because the failure mode this class prevents is
 * about what is on the wire, not what Micrometer's in-memory config object says — the two can
 * diverge if a merge direction is backwards or a registry-specific histogram flag is not honored.
 */
class RequestDurationHistogramTest {

    /**
     * Bucket boundaries as they appear in Prometheus's {@code le=} label, in ascending order.
     * Mirrors {@link RequestDurationHistogram#BOUNDARIES}; kept as a separate literal (not
     * derived from the production constant) so a change to the production boundaries has to be
     * a deliberate, visible edit to this list too.
     */
    private static final List<String> EXPECTED_LE = List.of(
            "0.05", "0.1", "0.25", "0.4", "0.5", "1.0", "2.0", "5.0", "+Inf");

    @Test
    void requestDurationMeterGetsExplicitHistogramBuckets() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // Configure BEFORE the timer is registered: a MeterFilter only applies to meters
        // registered after it is added, exactly like production startup order.
        RequestDurationHistogram.configure(registry);

        Timer timer = Timer.builder("api_gateway.request.duration").register(registry);
        timer.record(Duration.ofMillis(150));
        timer.record(Duration.ofSeconds(3));

        String scraped = registry.scrape();
        String bucketLines = extractLines(scraped, "api_gateway_request_duration_seconds_bucket");

        assertThat(bucketLines)
                .as("request-duration histogram must publish _bucket series once configured:\n%s", scraped)
                .isNotEmpty();
        for (String le : EXPECTED_LE) {
            assertThat(bucketLines)
                    .as("missing le=\"%s\" bucket boundary; full output:\n%s", le, bucketLines)
                    .contains("le=\"" + le + "\"");
        }
    }

    /**
     * {@link RequestDurationHistogram} scopes its filter to {@code *.request.duration} on
     * purpose (that is Armeria's exact meter name for the decorator's request timer) — it must
     * not turn on histogram buckets for every timer in the registry, which would reintroduce the
     * cardinality cost the design deliberately avoided for meters that were never part of this
     * contract.
     */
    @Test
    void unrelatedMeterDoesNotGetHistogramBuckets() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        RequestDurationHistogram.configure(registry);

        Timer timer = Timer.builder("api_gateway.response.duration").register(registry);
        timer.record(Duration.ofMillis(150));

        String scraped = registry.scrape();
        assertThat(scraped).doesNotContain("api_gateway_response_duration_seconds_bucket");
    }

    @Test
    void nullRegistryIsIgnored() {
        RequestDurationHistogram.configure(null);   // must not throw
    }

    private static String extractLines(String scraped, String prefix) {
        StringBuilder out = new StringBuilder();
        for (String line : scraped.split("\n")) {
            if (line.startsWith(prefix)) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
