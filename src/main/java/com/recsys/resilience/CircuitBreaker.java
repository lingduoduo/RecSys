package com.recsys.resilience;

import java.util.concurrent.atomic.AtomicBoolean;
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

    private final int failureThreshold;
    private final long cooldownMs;
    private final LongSupplier clockMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0L);
    private final AtomicBoolean probing = new AtomicBoolean(false);

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

    /** CLOSED → true; OPEN → false; HALF_OPEN → exactly one probe wins via CAS. */
    public boolean tryAcquire() {
        State s = state();
        if (s == State.CLOSED) return true;
        if (s == State.OPEN)   return false;
        return probing.compareAndSet(false, true);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        probing.set(false);
    }

    public void recordFailure() {
        probing.set(false);
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAtMs.set(clockMs.getAsLong());
        }
    }

    public int failureCount() {
        return consecutiveFailures.get();
    }
}
