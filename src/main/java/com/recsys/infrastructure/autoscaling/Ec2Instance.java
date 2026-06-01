package com.recsys.infrastructure.autoscaling;

import java.util.Objects;

public record Ec2Instance(
        String instanceId,
        String privateIpAddress,
        String instanceType,
        String availabilityZone,
        InstanceState state
) {
    public Ec2Instance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(privateIpAddress, "privateIpAddress");
        Objects.requireNonNull(instanceType, "instanceType");
        Objects.requireNonNull(availabilityZone, "availabilityZone");
        Objects.requireNonNull(state, "state");
        if (instanceId.isBlank()) throw new IllegalArgumentException("instanceId must not be blank");
    }

    public Ec2Instance withState(InstanceState newState) {
        return new Ec2Instance(instanceId, privateIpAddress, instanceType, availabilityZone, newState);
    }

    public boolean isRunning() {
        return state == InstanceState.RUNNING;
    }
}
