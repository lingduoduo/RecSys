package com.recsys.reliability;
import com.recsys.metrics.OnlineServingMetricsService;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OnlineCapacityServiceTest {

    @Test
    void snapshot_combinesTargetsWithObservedTraffic() {
        var metrics = new OnlineServingMetricsService(10);
        for (int i = 0; i < 20; i++) {
            metrics.recordSuccess(10L, "online");
        }
        var shedder = new OnlineLoadShedder(10, 0.90);
        var capacity = new OnlineCapacityService(2_000_000L, 8_000L, 20_000L);

        var snapshot = capacity.snapshot(metrics.snapshot(), shedder.snapshot());

        assertThat(snapshot.targetDau()).isEqualTo(2_000_000L);
        assertThat(snapshot.peakQps()).isEqualTo(8_000L);
        assertThat(snapshot.peakTps()).isEqualTo(20_000L);
        assertThat(snapshot.observedQps()).isCloseTo(2.0, within(1e-9));
        assertThat(snapshot.qpsUtilization()).isCloseTo(2.0 / 8_000.0, within(1e-9));
        assertThat(snapshot.peakShaving()).contains("Redis + MQ/Kafka");
    }

    @Test
    void headroomQps_reflectsRemainingBudget() {
        var metrics = new OnlineServingMetricsService(10);
        for (int i = 0; i < 20; i++) {
            metrics.recordSuccess(10L, "online");
        }
        var shedder = new OnlineLoadShedder(10, 0.90);
        var capacity = new OnlineCapacityService(2_000_000L, 8_000L, 20_000L);

        var snapshot = capacity.snapshot(metrics.snapshot(), shedder.snapshot());

        // observedQps ≈ 2.0, peakQps = 8000 → headroom ≈ 7998.0
        assertThat(snapshot.headroomQps()).isCloseTo(8_000.0 - 2.0, within(1.0));
        assertThat(snapshot.overloaded()).isFalse();
    }

    @Test
    void overloaded_trueWhenQpsExceedsPeak() {
        var shedder = new OnlineLoadShedder(10, 0.90);
        // peakQps = 1 with a 1-second window: 1 request → qps = 1.0 → utilization = 1.0
        var capacity = new OnlineCapacityService(2_000_000L, 1L, 20_000L);
        var metrics = new OnlineServingMetricsService(1);
        metrics.recordSuccess(5L, "online");

        var snapshot = capacity.snapshot(metrics.snapshot(), shedder.snapshot());

        assertThat(snapshot.overloaded()).isTrue();
        assertThat(snapshot.headroomQps()).isZero();
    }

    @Test
    void overloaded_trueWhenLoadShedderIsDraining() {
        var metrics = new OnlineServingMetricsService(10);
        var shedder = new OnlineLoadShedder(2, 0.75);
        shedder.tryAcquire();
        shedder.tryAcquire(); // 100% utilization >= 0.75 drain threshold
        var capacity = new OnlineCapacityService(2_000_000L, 8_000L, 20_000L);

        var snapshot = capacity.snapshot(metrics.snapshot(), shedder.snapshot());

        assertThat(snapshot.overloaded()).isTrue();
    }
}
