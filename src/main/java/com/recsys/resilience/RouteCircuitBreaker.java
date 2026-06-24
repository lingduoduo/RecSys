package com.recsys.resilience;

/**
 * Per-route circuit breaker for the API gateway. Delegates the CLOSED/OPEN/HALF_OPEN
 * state machine to the shared {@link CircuitBreaker}; keeps this class's public State
 * enum and method surface for gateway callers.
 */
public final class RouteCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public static final int  DEFAULT_FAILURE_THRESHOLD = 5;
    public static final long DEFAULT_COOLDOWN_MS       = 10_000L;

    private final CircuitBreaker delegate;

    public RouteCircuitBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN_MS);
    }

    public RouteCircuitBreaker(int failureThreshold, long cooldownMs) {
        this.delegate = new CircuitBreaker(failureThreshold, cooldownMs);
    }

    public State state() {
        return switch (delegate.state()) {
            case CLOSED    -> State.CLOSED;
            case OPEN      -> State.OPEN;
            case HALF_OPEN -> State.HALF_OPEN;
        };
    }

    public boolean tryAcquire()   { return delegate.tryAcquire(); }
    public void    recordSuccess(){ delegate.recordSuccess(); }
    public void    recordFailure(){ delegate.recordFailure(); }
}
