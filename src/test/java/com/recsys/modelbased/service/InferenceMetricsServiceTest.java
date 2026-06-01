package com.recsys.modelbased.service;

import com.recsys.modelbased.config.HealthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InferenceMetricsServiceTest {

    private InferenceMetricsService metrics;

    @BeforeEach
    void setUp() {
        var props = new HealthProperties();
        props.setWindowSeconds(60);
        metrics = new InferenceMetricsService(props);
    }

    @Test
    void initialSnapshot_allZeros() {
        InferenceMetricsService.Snapshot snap = metrics.snapshot();
        assertThat(snap.totalRequests()).isZero();
        assertThat(snap.recentRequests()).isZero();
        assertThat(snap.recentFailureRate()).isZero();
        assertThat(snap.recentAvgLatencyMs()).isZero();
    }

    @Test
    void recordSuccess_incrementsCountersAndLatency() {
        metrics.recordSuccess(100L);
        metrics.recordSuccess(200L);

        InferenceMetricsService.Snapshot snap = metrics.snapshot();
        assertThat(snap.totalRequests()).isEqualTo(2);
        assertThat(snap.successCount()).isEqualTo(2);
        assertThat(snap.failureCount()).isZero();
        assertThat(snap.recentRequests()).isEqualTo(2);
        assertThat(snap.recentFailureRate()).isCloseTo(0.0, within(1e-9));
        assertThat(snap.recentAvgLatencyMs()).isCloseTo(150.0, within(1e-9));
    }

    @Test
    void recordFailure_reflectedInFailureRate() {
        metrics.recordSuccess(50L);
        metrics.recordFailure(50L);

        InferenceMetricsService.Snapshot snap = metrics.snapshot();
        assertThat(snap.recentFailureRate()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void runningTotals_remainAccurateAfterMixedRequests() {
        metrics.recordSuccess(100L);
        metrics.recordSuccess(200L);
        metrics.recordFailure(300L);

        InferenceMetricsService.Snapshot snap = metrics.snapshot();
        assertThat(snap.recentRequests()).isEqualTo(3);
        assertThat(snap.recentFailureRate()).isCloseTo(1.0 / 3, within(1e-9));
        // avg = (100+200+300)/3 = 200
        assertThat(snap.recentAvgLatencyMs()).isCloseTo(200.0, within(1e-9));
    }

    @Test
    void variantMetrics_recordedIndependentlyOfWindow() {
        metrics.recordSuccess(10L, "control", "v1");
        metrics.recordSuccess(20L, "treatment", "v2");
        metrics.recordFailure(5L, "treatment");

        InferenceMetricsService.ABTestSnapshot abSnap = metrics.abTestSnapshot("control");
        assertThat(abSnap.variants()).containsKey("control");
        assertThat(abSnap.variants()).containsKey("treatment");

        var control = abSnap.variants().get("control");
        assertThat(control.successRate()).isCloseTo(1.0, within(1e-9));

        var treatment = abSnap.variants().get("treatment");
        assertThat(treatment.successRate()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void throughputPerSecond_basedOnWindowSize() {
        var props = new HealthProperties();
        props.setWindowSeconds(10);
        var svc = new InferenceMetricsService(props);
        for (int i = 0; i < 5; i++) svc.recordSuccess(10L);

        InferenceMetricsService.Snapshot snap = svc.snapshot();
        assertThat(snap.throughputPerSecond()).isCloseTo(0.5, within(1e-9));
    }
}
