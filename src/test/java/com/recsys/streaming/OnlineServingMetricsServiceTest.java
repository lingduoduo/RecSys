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
}
