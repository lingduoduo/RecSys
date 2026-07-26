package com.recsys.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Shared CLOSED/OPEN/HALF_OPEN circuit-breaker state machine, consolidated from
 * {@code RouteCircuitBreaker} (gateway) and {@code RedisRateLimiter}'s embedded breaker.
 *
 * Thread-safe: all transitions are CAS-based, no global lock. The clock is injectable so
 * cooldown transitions can be tested deterministically.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Permit(long generation, boolean probe) {}

    private final int failureThreshold;
    private final long cooldownMs;
    private final LongSupplier clockMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0L);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong probeGeneration = new AtomicLong(-1L);

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        this(failureThreshold, cooldownMs, System::currentTimeMillis);
    }

    CircuitBreaker(int failureThreshold, long cooldownMs, LongSupplier clockMs) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (cooldownMs < 0)       throw new IllegalArgumentException("cooldownMs must be non-negative");
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.clockMs = clockMs;
    }

    public State state() {
        if (consecutiveFailures.get() < failureThreshold) return State.CLOSED;
        long elapsed = clockMs.getAsLong() - openedAtMs.get();
        return elapsed >= cooldownMs ? State.HALF_OPEN : State.OPEN;
    }

    public Permit tryAcquirePermit() {
        State current = state();
        long observed = generation.get();
        if (current == State.CLOSED) return new Permit(observed, false);
        if (current == State.OPEN) return null;
        return probeGeneration.compareAndSet(-1L, observed)
                ? new Permit(observed, true)
                : null;
    }

    public void recordSuccess(Permit permit) {
        if (permit == null || permit.generation() != generation.get()) return;
        if (state() == State.HALF_OPEN && !permit.probe()) return;
        consecutiveFailures.set(0);
        probeGeneration.set(-1L);
    }

    public void recordFailure(Permit permit) {
        if (permit == null || permit.generation() != generation.get()) return;
        State before = state();
        if (before == State.HALF_OPEN && !permit.probe()) return;
        if (consecutiveFailures.incrementAndGet() >= failureThreshold
                && generation.compareAndSet(permit.generation(), permit.generation() + 1)) {
            openedAtMs.set(clockMs.getAsLong());
            probeGeneration.set(-1L);
        }
    }

    /** CLOSED → true; OPEN → false; HALF_OPEN → exactly one probe wins via CAS. */
    public boolean tryAcquire() {
        return tryAcquirePermit() != null;
    }

    public void recordSuccess() {
        recordSuccess(new Permit(generation.get(), state() == State.HALF_OPEN));
    }

    public void recordFailure() {
        recordFailure(new Permit(generation.get(), state() == State.HALF_OPEN));
    }

    public int failureCount() {
        return consecutiveFailures.get();
    }
}
