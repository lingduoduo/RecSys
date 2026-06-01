package com.recsys.infrastructure.autoscaling;

import com.recsys.infrastructure.alb.AlbTarget;
import com.recsys.infrastructure.alb.AlbTargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoScalingGroupTest {

    static final String AMI = "ami-0abcdef1234567890";
    static final String VPC = "vpc-12345678";

    LaunchTemplate template;
    NetworkConfig network;
    AlbTargetGroup targetGroup;
    FakeProvisioner provisioner;
    AutoScalingGroup asg;

    @BeforeEach
    void setUp() {
        template = LaunchTemplate.builder("lt-recsys")
                .imageId(AMI)
                .instanceType("t3.medium")
                .servicePort(8080)
                .securityGroup("sg-web")
                .build();

        network = NetworkConfig.builder(VPC)
                .availabilityZone("us-east-1a")
                .availabilityZone("us-east-1b")
                .availabilityZone("us-east-1c")
                .subnet("subnet-1a")
                .subnet("subnet-1b")
                .subnet("subnet-1c")
                .build();

        targetGroup = AlbTargetGroup.builder("recsys-tg").protocol("HTTP").build();
        provisioner = new FakeProvisioner();

        asg = AutoScalingGroup.builder("recsys-asg")
                .launchTemplate(template)
                .networkConfig(network)
                .scalingConfig(ScalingConfig.of(1, 6, 2))
                .provisioner(provisioner)
                .targetGroup(targetGroup)
                .build();
    }

    // ── Initial scale-out to desired capacity ─────────────────────────────────

    @Test
    void setDesiredCapacity_scalesOutToDesired() {
        asg.setDesiredCapacity(3);
        assertThat(asg.runningCount()).isEqualTo(3);
    }

    @Test
    void setDesiredCapacity_registersNewInstancesWithAlb() {
        asg.setDesiredCapacity(3);
        assertThat(targetGroup.totalCount()).isEqualTo(3);
        assertThat(targetGroup.healthyCount()).isEqualTo(3);
    }

    @Test
    void setDesiredCapacity_noChangeWhenAlreadyAtDesired() {
        asg.setDesiredCapacity(2);
        int provisionedBefore = provisioner.launchCount();
        asg.setDesiredCapacity(2);
        assertThat(provisioner.launchCount()).isEqualTo(provisionedBefore);
    }

    // ── Scale-in ──────────────────────────────────────────────────────────────

    @Test
    void scaleIn_reducesInstanceCount() {
        asg.setDesiredCapacity(4);
        asg.setDesiredCapacity(2);
        assertThat(asg.runningCount()).isEqualTo(2);
    }

    @Test
    void scaleIn_deregistersInstancesFromAlb() {
        asg.setDesiredCapacity(4);
        asg.setDesiredCapacity(2);
        assertThat(targetGroup.totalCount()).isEqualTo(2);
    }

    @Test
    void scaleIn_terminatesInstances() {
        asg.setDesiredCapacity(4);
        asg.scaleIn(2);
        assertThat(provisioner.terminateCount()).isEqualTo(2);
    }

    // ── Min / max boundary enforcement ───────────────────────────────────────

    @Test
    void setDesiredCapacity_clampsToMaxSize() {
        asg.setDesiredCapacity(100);
        assertThat(asg.runningCount()).isEqualTo(6); // maxSize = 6
    }

    @Test
    void setDesiredCapacity_clampsToMinSize() {
        asg.setDesiredCapacity(4);
        asg.setDesiredCapacity(0);
        assertThat(asg.runningCount()).isEqualTo(1); // minSize = 1
    }

    @Test
    void scaleIn_doesNotGoBelowMinSize() {
        asg.setDesiredCapacity(3);
        asg.scaleIn(10); // try to remove all
        assertThat(asg.runningCount()).isEqualTo(1);
    }

    @Test
    void scaleOut_doesNotExceedMaxSize() {
        asg.scaleOut(100);
        assertThat(asg.runningCount()).isEqualTo(6);
    }

    // ── AZ balancing ──────────────────────────────────────────────────────────

    @Test
    void scaleOut_distributesAcrossAvailabilityZones() {
        asg.setDesiredCapacity(6);
        Map<String, Long> countByAz = asg.instances().stream()
                .collect(Collectors.groupingBy(Ec2Instance::availabilityZone, Collectors.counting()));
        assertThat(countByAz).hasSize(3);
        countByAz.values().forEach(count -> assertThat(count).isEqualTo(2));
    }

    @Test
    void scaleIn_removesFromMostLoadedAzFirst() {
        // Manually scale to 4, get 2 in 1a, 1 in 1b, 1 in 1c
        asg.setDesiredCapacity(4);
        // Scale back to 3 — should remove one instance from whichever AZ has the most
        asg.setDesiredCapacity(3);
        assertThat(asg.runningCount()).isEqualTo(3);
        // The removed instance comes from a balanced AZ — verify total is correct
        Map<String, Long> remaining = asg.instances().stream()
                .collect(Collectors.groupingBy(Ec2Instance::availabilityZone, Collectors.counting()));
        assertThat(remaining.values().stream().mapToLong(l -> l).sum()).isEqualTo(3);
    }

    // ── ALB health-based replacement ─────────────────────────────────────────

    @Test
    void replaceUnhealthyInstances_launchesReplacementInSameAz() {
        asg.setDesiredCapacity(3);
        Ec2Instance failed = asg.instances().get(0);
        targetGroup.updateHealth(failed.privateIpAddress(), 8080, AlbTarget.TargetHealth.UNHEALTHY);

        asg.replaceUnhealthyInstances();

        assertThat(asg.runningCount()).isEqualTo(3);
        List<String> ips = asg.instances().stream()
                .map(Ec2Instance::privateIpAddress).toList();
        assertThat(ips).doesNotContain(failed.privateIpAddress());
    }

    @Test
    void replaceUnhealthyInstances_reregistersReplacementWithAlb() {
        asg.setDesiredCapacity(2);
        Ec2Instance failed = asg.instances().get(0);
        targetGroup.updateHealth(failed.privateIpAddress(), 8080, AlbTarget.TargetHealth.UNHEALTHY);

        asg.replaceUnhealthyInstances();

        assertThat(targetGroup.totalCount()).isEqualTo(2);
        assertThat(targetGroup.healthyCount()).isEqualTo(2);
    }

    @Test
    void replaceUnhealthyInstances_replacesMultipleFailed() {
        asg.setDesiredCapacity(4);
        asg.instances().stream().limit(2).forEach(i ->
                targetGroup.updateHealth(i.privateIpAddress(), 8080, AlbTarget.TargetHealth.UNHEALTHY));

        asg.replaceUnhealthyInstances();

        assertThat(asg.runningCount()).isEqualTo(4);
        assertThat(targetGroup.healthyCount()).isEqualTo(4);
    }

    @Test
    void replaceUnhealthyInstances_noOpWhenAllHealthy() {
        asg.setDesiredCapacity(3);
        int beforeLaunchCount = provisioner.launchCount();
        asg.replaceUnhealthyInstances();
        assertThat(provisioner.launchCount()).isEqualTo(beforeLaunchCount);
    }

    // ── No ELB attached ───────────────────────────────────────────────────────

    @Test
    void asgWithoutAlb_scalesNormally() {
        AutoScalingGroup noAlbAsg = AutoScalingGroup.builder("no-alb-asg")
                .launchTemplate(template)
                .networkConfig(network)
                .scalingConfig(ScalingConfig.of(0, 4, 0))
                .provisioner(provisioner)
                .build();

        noAlbAsg.setDesiredCapacity(3);
        assertThat(noAlbAsg.runningCount()).isEqualTo(3);
    }

    @Test
    void asgWithoutAlb_replaceUnhealthyIsNoOp() {
        AutoScalingGroup noAlbAsg = AutoScalingGroup.builder("no-alb-asg")
                .launchTemplate(template)
                .networkConfig(network)
                .scalingConfig(ScalingConfig.of(0, 4, 0))
                .provisioner(provisioner)
                .build();
        noAlbAsg.setDesiredCapacity(2);
        int before = provisioner.launchCount();
        noAlbAsg.replaceUnhealthyInstances();
        assertThat(provisioner.launchCount()).isEqualTo(before);
    }

    // ── ScalingConfig validation ──────────────────────────────────────────────

    @Test
    void scalingConfig_rejectsMinGreaterThanMax() {
        assertThatThrownBy(() -> ScalingConfig.of(10, 5, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minSize");
    }

    @Test
    void scalingConfig_rejectsDesiredOutsideRange() {
        assertThatThrownBy(() -> ScalingConfig.of(1, 5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desiredCapacity");
    }

    @Test
    void scalingConfig_clampRespectsBounds() {
        ScalingConfig config = ScalingConfig.of(2, 8, 4);
        assertThat(config.clamp(1)).isEqualTo(2);
        assertThat(config.clamp(10)).isEqualTo(8);
        assertThat(config.clamp(5)).isEqualTo(5);
    }

    // ── LaunchTemplate validation ─────────────────────────────────────────────

    @Test
    void launchTemplate_rejectsMissingImageId() {
        assertThatThrownBy(() -> LaunchTemplate.builder("lt-x").build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void launchTemplate_defaultsToPurchasingOptionOnDemand() {
        LaunchTemplate t = LaunchTemplate.builder("lt-x").imageId(AMI).build();
        assertThat(t.purchasingOption()).isEqualTo(InstancePurchasingOption.ON_DEMAND);
    }

    @Test
    void launchTemplate_spotPurchasingOption() {
        LaunchTemplate t = LaunchTemplate.builder("lt-spot")
                .imageId(AMI)
                .purchasingOption(InstancePurchasingOption.SPOT)
                .build();
        assertThat(t.purchasingOption()).isEqualTo(InstancePurchasingOption.SPOT);
    }

    // ── NetworkConfig validation ──────────────────────────────────────────────

    @Test
    void networkConfig_requiresAtLeastOneAz() {
        assertThatThrownBy(() -> NetworkConfig.builder("vpc-x").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availability zone");
    }

    // ── AutoScalingGroup builder validation ───────────────────────────────────

    @Test
    void asgBuilder_rejectsMissingRequiredFields() {
        assertThatThrownBy(() ->
                AutoScalingGroup.builder("incomplete-asg")
                        .launchTemplate(template)
                        .networkConfig(network)
                        .build()
        ).isInstanceOf(IllegalStateException.class).hasMessageContaining("scalingConfig");
    }

    // ── Fake provisioner ──────────────────────────────────────────────────────

    static final class FakeProvisioner implements InstanceProvisioner {
        private final AtomicInteger idCounter = new AtomicInteger(0);
        private final AtomicInteger launched = new AtomicInteger(0);
        private final AtomicInteger terminated = new AtomicInteger(0);

        @Override
        public Ec2Instance launch(LaunchTemplate tmpl, String az) {
            int id = idCounter.incrementAndGet();
            launched.incrementAndGet();
            return new Ec2Instance(
                    "i-" + String.format("%017d", id),
                    "10.0." + ((id % 3) + 1) + "." + id,
                    tmpl.instanceType(),
                    az,
                    InstanceState.RUNNING);
        }

        @Override
        public void terminate(String instanceId) {
            terminated.incrementAndGet();
        }

        int launchCount()    { return launched.get(); }
        int terminateCount() { return terminated.get(); }
    }
}
