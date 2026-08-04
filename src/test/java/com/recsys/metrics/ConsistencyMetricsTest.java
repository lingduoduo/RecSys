package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistencyMetricsTest {
    @Test void registersRequiredBoundedMetersWithoutIdentityTags() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ConsistencyMetrics(registry);

        metrics.recordDelivered(ConsistencyMetrics.Destination.KAFKA_ONLINE, Duration.ofSeconds(3));
        metrics.recordDeliveryFailure(ConsistencyMetrics.Destination.KAFKA_ONLINE);
        metrics.recordAsyncDrop(ConsistencyMetrics.EventType.ONLINE_INTERACTION);
        metrics.recordTokenValidation(ConsistencyMetrics.TokenOutcome.VALID);
        metrics.recordWait(ConsistencyMetrics.WaitOutcome.APPLIED, Duration.ofMillis(12));
        metrics.updatePendingEvents(4);
        metrics.updateReplicaLag(new ConsistencyMetrics.ReplicaLag(true, 0.25));
        metrics.updateFeatureVersions(7, 11, Duration.ofSeconds(8));
        metrics.recordReconciliation(ConsistencyMetrics.ReconciliationOutcome.SCANNED);

        assertThat(registry.get("outbox_delivery_lag_seconds").tag("destination", "kafka_online").timer().count()).isOne();
        assertThat(registry.get("outbox_delivery_failures_total").tag("destination", "kafka_online").counter().count()).isOne();
        assertThat(registry.get("async_events_dropped_total").tag("event_type", "online_interaction").counter().count()).isOne();
        assertThat(registry.get("outbox_pending_events").gauge().value()).isEqualTo(4);
        assertThat(registry.get("redis_replica_lag_seconds").gauge().value()).isEqualTo(.25);
        assertThat(registry.get("redis_replica_lag_available").gauge().value()).isOne();
        assertThat(registry.get("redis_feature_version_min").gauge().value()).isEqualTo(7);
        assertThat(registry.get("redis_feature_version_max").gauge().value()).isEqualTo(11);
        assertThat(registry.get("redis_feature_version_age_seconds").gauge().value()).isEqualTo(8);
        assertThat(registry.get("consistency_token_validation_total").tag("outcome", "valid").counter().count()).isOne();
        assertThat(registry.get("consistency_wait_total").tag("outcome", "applied").counter().count()).isOne();
        assertThat(registry.get("consistency_wait_duration_seconds").timer().count()).isOne();
        assertThat(registry.get("reconciliation_events_total").tag("outcome", "scanned").counter().count()).isOne();

        Set<String> allowed = Set.of("destination", "event_type", "outcome");
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(allowed).contains(tag.getKey())));
    }

    @Test void unavailableReplicaLagIsNotReportedAsZero() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ConsistencyMetrics(registry);
        metrics.updateReplicaLag(new ConsistencyMetrics.ReplicaLag(false, 0));
        assertThat(registry.get("redis_replica_lag_available").gauge().value()).isZero();
        assertThat(registry.get("redis_replica_lag_seconds").gauge().value()).isNaN();
    }

    /**
     * PromQL in {@code k8s/base/prometheus-rules.yaml} names series, not Micrometer meters, and
     * the two are not the same string. A {@code Timer} named {@code outbox_delivery_lag_seconds}
     * is exposed as three derived series ({@code _count}, {@code _sum}, {@code _max}) and none of
     * them is the bare meter name — so an alert written against the meter name matches nothing
     * and stays silent forever. This pins the exact scraped text the alerts depend on, so a
     * Micrometer upgrade that renames or drops a series fails here rather than in production.
     */
    @Test void prometheusExpositionPinsFeatureAvailabilityAndDeliveryLagNames() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var metrics = new ConsistencyMetrics(registry);

        assertThat(registry.get("redis_feature_version_sample_available").gauge().value())
                .as("no sample has succeeded yet, so availability must start at 0 — a zero age "
                        + "before the first successful sample is not evidence of fresh data")
                .isZero();

        metrics.updateFeatureVersions(7, 11, Duration.ofSeconds(8));
        metrics.recordDelivered(ConsistencyMetrics.Destination.KAFKA_ONLINE, Duration.ofSeconds(45));

        assertThat(registry.get("redis_feature_version_sample_available").gauge().value()).isOne();
        assertThat(registry.scrape())
                .contains("redis_feature_version_sample_available 1.0")
                .contains("outbox_delivery_lag_seconds_max")
                .contains("destination=\"kafka_online\"");
    }

    /**
     * The freshness alerts read age and availability as two independent signals. Clearing age on
     * a failed sample would make a stalled sampler look instantaneously fresh; clearing
     * availability without preserving age would throw away the operator's only clue about how
     * old the last known-good view was.
     */
    @Test void featureSampleCanReturnToUnavailableWithoutErasingLastGoodValues() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ConsistencyMetrics(registry);

        metrics.updateFeatureVersions(7, 11, Duration.ofSeconds(8));
        metrics.markFeatureVersionSampleUnavailable();

        assertThat(registry.get("redis_feature_version_sample_available").gauge().value()).isZero();
        assertThat(registry.get("redis_feature_version_age_seconds").gauge().value()).isEqualTo(8);
        assertThat(registry.get("redis_feature_version_max").gauge().value()).isEqualTo(11);
    }

    @Test void facadesForSameRegistryShareStateAndRegisterMetersOnce() {
        var registry = new SimpleMeterRegistry();
        var first = new ConsistencyMetrics(registry, () -> 7);
        var second = new ConsistencyMetrics(registry);
        first.recordDeliveryFailure(ConsistencyMetrics.Destination.KAFKA_ONLINE);
        second.recordDeliveryFailure(ConsistencyMetrics.Destination.KAFKA_ONLINE);
        second.updateInFlightEvents(3);
        assertThat(registry.find("outbox_delivery_failures_total").meters()).hasSize(2);
        assertThat(registry.get("outbox_delivery_failures_total").tag("destination", "kafka_online").counter().count()).isEqualTo(2);
        assertThat(registry.get("outbox_pending_events").gauge().value()).isEqualTo(7);
        assertThat(registry.get("outbox_in_flight_events").gauge().value()).isEqualTo(3);
    }
}
