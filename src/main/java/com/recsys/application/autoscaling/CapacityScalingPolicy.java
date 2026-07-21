package com.recsys.application.autoscaling;

/**
 * Pure target-tracking scaling policy (the algorithm AWS ASG target-tracking and Kubernetes HPA
 * both use): desired = ceil(running * utilization / targetUtilization). A surge override raises the
 * desired by {@code surgeStep} above the current count when the system reports overload, so a sudden
 * spike does not wait for utilization to be observed. Clamping to [min,max] is the actuator's job.
 */
public final class CapacityScalingPolicy {
    public static final double DEFAULT_TARGET_UTILIZATION = 0.7;
    public static final int    DEFAULT_SURGE_STEP         = 2;

    private final double targetUtilization;
    private final int surgeStep;

    public CapacityScalingPolicy() {
        this(DEFAULT_TARGET_UTILIZATION, DEFAULT_SURGE_STEP);
    }

    public CapacityScalingPolicy(double targetUtilization, int surgeStep) {
        if (!(targetUtilization > 0.0) || Double.isInfinite(targetUtilization)) {
            throw new IllegalArgumentException("targetUtilization must be finite and > 0");
        }
        if (surgeStep < 1) {
            throw new IllegalArgumentException("surgeStep must be >= 1");
        }
        this.targetUtilization = targetUtilization;
        this.surgeStep = surgeStep;
    }

    public int desiredReplicas(int currentRunning, double utilization, boolean surge) {
        double util = (Double.isNaN(utilization) || Double.isInfinite(utilization) || utilization < 0.0)
                ? 0.0 : utilization;
        int base = Math.max(1, currentRunning);
        int raw = (int) Math.ceil(base * util / targetUtilization);
        if (surge) {
            raw = Math.max(raw, currentRunning + surgeStep);
        }
        return Math.max(0, raw);
    }

    public double targetUtilization() { return targetUtilization; }
    public int surgeStep() { return surgeStep; }
}
