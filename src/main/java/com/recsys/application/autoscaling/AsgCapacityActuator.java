package com.recsys.application.autoscaling;

import com.recsys.infrastructure.autoscaling.AutoScalingGroup;

import java.util.Objects;

/** Adapts the in-memory {@link AutoScalingGroup} simulation to {@link CapacityActuator}. */
public final class AsgCapacityActuator implements CapacityActuator {
    private final AutoScalingGroup asg;

    public AsgCapacityActuator(AutoScalingGroup asg) {
        this.asg = Objects.requireNonNull(asg, "asg");
    }

    @Override public int runningCount() { return asg.runningCount(); }
    @Override public int minSize() { return asg.scalingConfig().minSize(); }
    @Override public int maxSize() { return asg.scalingConfig().maxSize(); }
    @Override public void setDesiredCapacity(int desired) { asg.setDesiredCapacity(desired); }
}
