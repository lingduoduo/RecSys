package com.recsys.application.autoscaling;

import com.recsys.infrastructure.autoscaling.AutoScalingGroup;
import com.recsys.infrastructure.autoscaling.Ec2Instance;
import com.recsys.infrastructure.autoscaling.InstanceProvisioner;
import com.recsys.infrastructure.autoscaling.InstanceState;
import com.recsys.infrastructure.autoscaling.LaunchTemplate;
import com.recsys.infrastructure.autoscaling.NetworkConfig;
import com.recsys.infrastructure.autoscaling.ScalingConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AsgCapacityActuatorTest {

    /** Local fake — the one in AutoScalingGroupTest is package-private in a different package. */
    static final class FakeProvisioner implements InstanceProvisioner {
        private final AtomicInteger id = new AtomicInteger();
        @Override public Ec2Instance launch(LaunchTemplate t, String az) {
            int n = id.incrementAndGet();
            return new Ec2Instance("i-" + String.format("%017d", n),
                    "10.0.0." + n, t.instanceType(), az, InstanceState.RUNNING);
        }
        @Override public void terminate(String instanceId) { }
    }

    static AutoScalingGroup newAsg() {
        LaunchTemplate template = LaunchTemplate.builder("lt-recsys")
                .imageId("ami-0abcdef1234567890").instanceType("t3.medium")
                .servicePort(8080).securityGroup("sg-web").build();
        NetworkConfig network = NetworkConfig.builder("vpc-12345678")
                .availabilityZone("us-east-1a").availabilityZone("us-east-1b").availabilityZone("us-east-1c")
                .subnet("subnet-1a").subnet("subnet-1b").subnet("subnet-1c")
                .build();
        return AutoScalingGroup.builder("recsys-asg")
                .launchTemplate(template).networkConfig(network)
                .scalingConfig(ScalingConfig.of(1, 6, 2))
                .provisioner(new FakeProvisioner())
                .build();
    }

    @Test void exposesRunningCountAndBounds() {
        AutoScalingGroup asg = newAsg();
        asg.setDesiredCapacity(2);
        AsgCapacityActuator actuator = new AsgCapacityActuator(asg);
        assertThat(actuator.runningCount()).isEqualTo(2);
        assertThat(actuator.minSize()).isEqualTo(1);
        assertThat(actuator.maxSize()).isEqualTo(6);
    }

    @Test void setDesiredCapacityDrivesAsgAndClampsToMax() {
        AutoScalingGroup asg = newAsg();
        AsgCapacityActuator actuator = new AsgCapacityActuator(asg);
        actuator.setDesiredCapacity(20);                 // above max 6
        assertThat(actuator.runningCount()).isEqualTo(6); // ASG clamps
    }
}
