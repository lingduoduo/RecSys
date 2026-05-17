package com.recsys.streaming;

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
}
