package com.recsys.application.autoscaling;

/** Actuation seam the controller drives — decouples it from the concrete AutoScalingGroup. */
public interface CapacityActuator {
    int runningCount();
    int minSize();
    int maxSize();
    void setDesiredCapacity(int desired);
}
