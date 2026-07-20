package com.recsys.application.autoscaling;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference signal-driven capacity controller over the in-memory {@link AsgCapacityActuator}
 * simulation. Each {@link #tick()} reads a {@link CapacitySignal}, computes a target-tracking
 * desired capacity, clamps it to the actuator's [min,max], and applies it via the actuator only
 * when it differs from the running count AND the direction's cooldown has elapsed (asymmetric:
 * fast scale-out, slow scale-in — mirroring the model-serving HPA).
 *
 * <p><b>Not wired into production.</b> Real scaling in EKS is HPA + cluster-autoscaler; this exists
 * to close the otherwise-dangling {@code AutoScalingGroup} loop as a tested reference.
 */
public final class CapacityController {
    private static final Logger log = LoggerFactory.getLogger(CapacityController.class);

    public static final long DEFAULT_SCALE_OUT_COOLDOWN_MS = 60_000L;
    public static final long DEFAULT_SCALE_IN_COOLDOWN_MS   = 300_000L;

    private final CapacityActuator actuator;
    private final CapacitySignalSource signals;
    private final CapacityScalingPolicy policy;
    private final long scaleOutCooldownMs;
    private final long scaleInCooldownMs;
    private final LongSupplier clockMs;

    // Initialised far in the past (without underflow) so the first tick can always act.
    private long lastScaleOutMs = Long.MIN_VALUE / 2;
    private long lastScaleInMs  = Long.MIN_VALUE / 2;

    public CapacityController(CapacityActuator actuator, CapacitySignalSource signals,
                              CapacityScalingPolicy policy) {
        this(actuator, signals, policy, DEFAULT_SCALE_OUT_COOLDOWN_MS, DEFAULT_SCALE_IN_COOLDOWN_MS,
                System::currentTimeMillis);
    }

    public CapacityController(CapacityActuator actuator, CapacitySignalSource signals,
                              CapacityScalingPolicy policy, long scaleOutCooldownMs,
                              long scaleInCooldownMs, LongSupplier clockMs) {
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.scaleOutCooldownMs = Math.max(0L, scaleOutCooldownMs);
        this.scaleInCooldownMs = Math.max(0L, scaleInCooldownMs);
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
    }

    public synchronized ScalingDecision tick() {
        CapacitySignal s = signals.read();
        int running = actuator.runningCount();
        int desired = clamp(policy.desiredReplicas(running, s.utilization(), s.surge()),
                actuator.minSize(), actuator.maxSize());
        if (desired == running) {
            return new ScalingDecision(running, desired, false, "steady");
        }
        boolean out = desired > running;
        long now = clockMs.getAsLong();
        long sinceLast = out ? now - lastScaleOutMs : now - lastScaleInMs;
        long cooldown = out ? scaleOutCooldownMs : scaleInCooldownMs;
        if (sinceLast < cooldown) {
            return new ScalingDecision(running, desired, false, "cooldown");
        }
        actuator.setDesiredCapacity(desired);
        if (out) { lastScaleOutMs = now; } else { lastScaleInMs = now; }
        return new ScalingDecision(running, desired, true, out ? "scaled-out" : "scaled-in");
    }

    /**
     * Calls {@link #tick()} inside a try/catch that swallows RuntimeExceptions and logs them.
     * Ensures the schedule survives even if the signal source or policy throws.
     */
    void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("CapacityController.tick() failed; continuing schedule", e);
        }
    }

    /** Optional convenience: run {@link #tick()} on a fixed-delay schedule. Not used in production. */
    public void start(ScheduledExecutorService scheduler, Duration interval) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(interval, "interval");
        long ms = Math.max(1L, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::tickSafely, ms, ms, TimeUnit.MILLISECONDS);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public record ScalingDecision(int running, int desired, boolean applied, String reason) {}
}
