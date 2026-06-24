package com.recsys.resilience;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-route circuit breaker for the API gateway.
 *
 * States:
 *   CLOSED   — normal operation; all requests pass through.
 *   OPEN     — upstream repeatedly failed; requests are rejected fast (503) for
 *              {@code cooldownMs} without hitting the upstream at all. This prevents
 *              a departing Cloud Map endpoint from receiving a burst of traffic during
 *              the brief window between DNS TTL expiry and Cloud Map deregistration.
 *   HALF_OPEN — after the cooldown, one probe request is allowed through. If it
 *              succeeds the circuit closes; if it fails the cooldown resets.
 *
 * Thread-safe. All state transitions are CAS-based with no global lock.
 */
public final class RouteCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public static final int  DEFAULT_FAILURE_THRESHOLD = 5;
    public static final long DEFAULT_COOLDOWN_MS       = 10_000L;

    private final int  failureThreshold;
    private final long cooldownMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong    openedAtMs          = new AtomicLong(0L);
    // Guards the single probe request allowed in HALF_OPEN. Set to true when a probe is
    // in flight; cleared by recordSuccess() or recordFailure() when the probe completes.
    private final AtomicBoolean probing             = new AtomicBoolean(false);

    public RouteCircuitBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN_MS);
    }

    public RouteCircuitBreaker(int failureThreshold, long cooldownMs) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (cooldownMs < 0)       throw new IllegalArgumentException("cooldownMs must be non-negative");
        this.failureThreshold = failureThreshold;
        this.cooldownMs       = cooldownMs;
    }

    public State state() {
        if (consecutiveFailures.get() < failureThreshold) return State.CLOSED;
        long elapsed = System.currentTimeMillis() - openedAtMs.get();
        return elapsed >= cooldownMs ? State.HALF_OPEN : State.OPEN;
    }

    /**
     * Returns {@code true} when the request should proceed.
     * CLOSED → always true.
     * OPEN   → always false (fast-fail).
     * HALF_OPEN → exactly one probe request is allowed via CAS; all concurrent callers
     *             get false until that probe completes (recordSuccess / recordFailure).
     */
    public boolean tryAcquire() {
        State s = state();
        if (s == State.CLOSED)   return true;
        if (s == State.OPEN)     return false;
        return probing.compareAndSet(false, true);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        probing.set(false);
    }

    public void recordFailure() {
        probing.set(false);
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAtMs.set(System.currentTimeMillis());
        }
    }
}
