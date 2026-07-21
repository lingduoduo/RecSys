package com.recsys.application.autoscaling;

import com.recsys.health.OnlineCapacityService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineCapacitySignalSourceTest {

    private static OnlineCapacityService.Snapshot snap(double qpsUtilization, boolean overloaded) {
        // Snapshot(targetDau, peakQps, peakTps, observedQps, qpsUtilization, headroomQps, overloaded, load, peakShaving)
        return new OnlineCapacityService.Snapshot(
                2_000_000L, 8_000L, 20_000L, 6_000.0, qpsUtilization, 2_000.0, overloaded, null, "x");
    }

    @Test void mapsUtilizationAndOverloaded() {
        OnlineCapacitySignalSource src = new OnlineCapacitySignalSource(() -> snap(0.75, true));
        CapacitySignal signal = src.read();
        assertThat(signal.utilization()).isEqualTo(0.75);
        assertThat(signal.surge()).isTrue();
    }

    @Test void notOverloadedIsNoSurge() {
        OnlineCapacitySignalSource src = new OnlineCapacitySignalSource(() -> snap(0.20, false));
        CapacitySignal signal = src.read();
        assertThat(signal.utilization()).isEqualTo(0.20);
        assertThat(signal.surge()).isFalse();
    }
}
