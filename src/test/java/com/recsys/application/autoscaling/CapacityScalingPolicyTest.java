package com.recsys.application.autoscaling;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapacityScalingPolicyTest {

    private final CapacityScalingPolicy policy = new CapacityScalingPolicy(); // target 0.7, surge 2

    @Test void targetTrackingScalesOut() {
        assertThat(policy.desiredReplicas(5, 1.40, false)).isEqualTo(10); // ceil(5*1.4/0.7)
    }

    @Test void targetTrackingScalesIn() {
        assertThat(policy.desiredReplicas(6, 0.35, false)).isEqualTo(3);  // ceil(6*0.35/0.7)
    }

    @Test void steadyAtTarget() {
        assertThat(policy.desiredReplicas(4, 0.70, false)).isEqualTo(4);
    }

    @Test void surgeOverridesLowUtilization() {
        assertThat(policy.desiredReplicas(3, 0.10, true)).isEqualTo(5);   // max(1, 3+2)
    }

    @Test void zeroRunningUsesBaseOne() {
        assertThat(policy.desiredReplicas(0, 0.70, false)).isEqualTo(1);  // ceil(1*0.7/0.7)
    }

    @Test void zeroUtilizationScalesToZero() {
        assertThat(policy.desiredReplicas(5, 0.0, false)).isEqualTo(0);
    }

    @Test void negativeOrNanUtilizationTreatedAsZero() {
        assertThat(policy.desiredReplicas(5, -3.0, false)).isEqualTo(0);
        assertThat(policy.desiredReplicas(5, Double.NaN, false)).isEqualTo(0);
    }

    @Test void invalidConfigRejected() {
        assertThatThrownBy(() -> new CapacityScalingPolicy(0.0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapacityScalingPolicy(0.7, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
