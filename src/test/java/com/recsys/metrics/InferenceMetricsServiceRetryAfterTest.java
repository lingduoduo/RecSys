package com.recsys.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.recsys.config.HealthProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceMetricsServiceRetryAfterTest {

    @Test
    void retryAfterSeconds_returnsOneWhenSnapshotIsNull() {
        assertThat(InferenceMetricsService.retryAfterSeconds(null)).isEqualTo(1);
    }

    @Test
    void retryAfterSeconds_returnsOneWhenNoLatencyRecorded() {
        InferenceMetricsService svc =
                new InferenceMetricsService(new HealthProperties(), new SimpleMeterRegistry());
        assertThat(InferenceMetricsService.retryAfterSeconds(svc.snapshot())).isEqualTo(1);
    }

    @Test
    void retryAfterSeconds_roundsLatencyUpToWholeSeconds() {
        InferenceMetricsService svc =
                new InferenceMetricsService(new HealthProperties(), new SimpleMeterRegistry());
        svc.recordSuccess(2400L);   // 2.4s -> ceil -> 3
        assertThat(InferenceMetricsService.retryAfterSeconds(svc.snapshot())).isEqualTo(3);
    }

    @Test
    void retryAfterSeconds_isClampedToTenSeconds() {
        InferenceMetricsService svc =
                new InferenceMetricsService(new HealthProperties(), new SimpleMeterRegistry());
        svc.recordSuccess(60_000L);  // 60s -> clamped
        assertThat(InferenceMetricsService.retryAfterSeconds(svc.snapshot())).isEqualTo(10);
    }
}
