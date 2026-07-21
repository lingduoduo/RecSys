package com.recsys.application.autoscaling;

import com.recsys.infrastructure.autoscaling.AutoScalingGroup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityControllerAsgTest {

    @Test void controllerDrivesRealAsgAndAsgClampsToMax() {
        AutoScalingGroup asg = AsgCapacityActuatorTest.newAsg(); // reuse the same construction
        asg.setDesiredCapacity(2);
        AsgCapacityActuator actuator = new AsgCapacityActuator(asg);

        // High utilization pushes desired well above max (6); the ASG must clamp.
        CapacitySignalSource hot = () -> new CapacitySignal(5.0, false);
        CapacityController controller = new CapacityController(
                actuator, hot, new CapacityScalingPolicy(), 0L, 0L, () -> 0L);

        CapacityController.ScalingDecision d = controller.tick();
        assertThat(d.applied()).isTrue();
        assertThat(d.desired()).isEqualTo(6);            // controller clamped to actuator.maxSize()
        assertThat(asg.runningCount()).isEqualTo(6);     // real ASG scaled out and clamped
    }
}
