package com.recsys.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OnlineServingMetricsServiceTest {

    @Test
    void initialSnapshot_hasNoTraffic() {
        var service = new OnlineServingMetricsService(10);

        var snapshot = service.snapshot();

        assertThat(snapshot.totalRequests()).isZero();
        assertThat(snapshot.qps()).isZero();
        assertThat(snapshot.recentFailureRate()).isZero();
        assertThat(snapshot.strategies()).isEmpty();
    }

    @Test
    void recordSuccess_tracksLatencyQpsAndStrategy() {
        var service = new OnlineServingMetricsService(10);

        service.recordSuccess(20L, "online");
        service.recordSuccess(40L, "online+model");
        service.recordSuccess(60L, "online+model");

        var snapshot = service.snapshot();
        assertThat(snapshot.totalRequests()).isEqualTo(3);
        assertThat(snapshot.successCount()).isEqualTo(3);
        assertThat(snapshot.failureCount()).isZero();
        assertThat(snapshot.recentAvgLatencyMs()).isCloseTo(40.0, within(1e-9));
        assertThat(snapshot.qps()).isCloseTo(0.3, within(1e-9));
        assertThat(snapshot.strategies()).containsKeys("online", "online+model");
        assertThat(snapshot.strategies().get("online+model").requests()).isEqualTo(2);
        assertThat(snapshot.strategies().get("online+model").avgLatencyMs()).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void recordRejected_countsAsFailureAndRejectedRequest() {
        var service = new OnlineServingMetricsService(10);

        service.recordSuccess(10L, "online");
        service.recordRejected();

        var snapshot = service.snapshot();
        assertThat(snapshot.totalRequests()).isEqualTo(2);
        assertThat(snapshot.failureCount()).isEqualTo(1);
        assertThat(snapshot.rejectedCount()).isEqualTo(1);
        assertThat(snapshot.recentFailureRate()).isCloseTo(0.5, within(1e-9));
        assertThat(snapshot.recentRejectedRate()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void strategySnapshot_exposesShareAndFailureRate() {
        var service = new OnlineServingMetricsService(10);

        service.recordSuccess(10L, "online");
        service.recordSuccess(20L, "online+model");
        service.recordFailure(5L, "online+model");

        var snapshot = service.snapshot();
        var onlineModel = snapshot.strategies().get("online+model");

        assertThat(onlineModel.requests()).isEqualTo(2);
        assertThat(onlineModel.failureCount()).isEqualTo(1);
        assertThat(onlineModel.failureRate()).isCloseTo(0.5, within(1e-9));
        // 2 out of 3 total requests
        assertThat(onlineModel.share()).isCloseTo(2.0 / 3.0, within(1e-9));

        var online = snapshot.strategies().get("online");
        assertThat(online.share()).isCloseTo(1.0 / 3.0, within(1e-9));
        assertThat(online.failureRate()).isZero();
    }

    @Test
    void percentiles_computedFromRecordedLatencies() {
        var service = new OnlineServingMetricsService(60);

        // Record 100 samples: 1 ms, 2 ms, …, 100 ms
        for (int i = 1; i <= 100; i++) {
            service.recordSuccess(i, "online");
        }

        var snap = service.snapshot();
        // p50 ≈ 50, p95 ≈ 95, p99 ≈ 99 — allow ±5 ms for reservoir rounding
        assertThat(snap.p50Ms()).isBetween(45L, 55L);
        assertThat(snap.p95Ms()).isBetween(90L, 100L);
        assertThat(snap.p99Ms()).isBetween(94L, 100L);
    }

    @Test
    void percentiles_zeroWhenNoRequests() {
        var service = new OnlineServingMetricsService(60);
        var snap = service.snapshot();
        assertThat(snap.p50Ms()).isZero();
        assertThat(snap.p95Ms()).isZero();
        assertThat(snap.p99Ms()).isZero();
    }

    @Test
    void registerGauges_exposesQpsAndPercentilesToMeterRegistry() {
        var service = new OnlineServingMetricsService(60);
        for (int i = 1; i <= 10; i++) {
            service.recordSuccess(i * 10L, "online"); // 10, 20, …, 100 ms
        }

        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        service.registerGauges(registry);

        // Gauges exist — values are computed lazily on first scrape
        assertThat(registry.find("online_serving_qps").gauge()).isNotNull();
        assertThat(registry.find("online_serving_p95_ms").gauge()).isNotNull();
        assertThat(registry.find("online_serving_p99_ms").gauge()).isNotNull();
        assertThat(registry.find("online_serving_failure_rate").gauge()).isNotNull();
        assertThat(registry.find("online_serving_rejected_rate").gauge()).isNotNull();

        // QPS > 0 after recording 10 requests in a 60-second window
        double qps = registry.find("online_serving_qps").gauge().value();
        assertThat(qps).isGreaterThan(0.0);
    }
}
