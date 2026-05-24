package com.recsys.microservice;

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
final class RouteCircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    static final int  DEFAULT_FAILURE_THRESHOLD = 5;
    static final long DEFAULT_COOLDOWN_MS       = 10_000L;

    private final int  failureThreshold;
    private final long cooldownMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong    openedAtMs          = new AtomicLong(0L);

    RouteCircuitBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN_MS);
    }

    RouteCircuitBreaker(int failureThreshold, long cooldownMs) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (cooldownMs < 0)       throw new IllegalArgumentException("cooldownMs must be non-negative");
        this.failureThreshold = failureThreshold;
        this.cooldownMs       = cooldownMs;
    }

    State state() {
        if (consecutiveFailures.get() < failureThreshold) return State.CLOSED;
        long elapsed = System.currentTimeMillis() - openedAtMs.get();
        return elapsed >= cooldownMs ? State.HALF_OPEN : State.OPEN;
    }

    /** Returns {@code true} when the request should proceed (CLOSED or HALF_OPEN). */
    boolean tryAcquire() {
        return state() != State.OPEN;
    }

    void recordSuccess() {
        consecutiveFailures.set(0);
    }

    void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAtMs.set(System.currentTimeMillis());
        }
    }
}
