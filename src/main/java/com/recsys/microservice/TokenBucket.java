package com.recsys.microservice;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class TokenBucket {
    private final double refillPerNano;
    private final int burst;
    private final LongSupplier tickerNanos;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(double ratePerSecond, int burst, LongSupplier tickerNanos) {
        this.refillPerNano = ratePerSecond / TimeUnit.SECONDS.toNanos(1);
        this.burst = burst;
        this.tickerNanos = tickerNanos;
        this.tokens = burst;
        this.lastRefillNanos = tickerNanos.getAsLong();
    }

    synchronized Decision tryAcquire(int needed) {
        refill();
        if (tokens >= needed) {
            tokens -= needed;
            return new Decision(true, burst, (int) Math.floor(tokens), Duration.ZERO);
        }
        long waitNanos = (long) Math.ceil((needed - tokens) / refillPerNano);
        return new Decision(false, burst, (int) Math.floor(tokens),
                Duration.ofNanos(Math.max(0L, waitNanos)));
    }

    synchronized Decision tryAcquire() {
        return tryAcquire(1);
    }

    private void refill() {
        long now = tickerNanos.getAsLong();
        long elapsed = Math.max(0L, now - lastRefillNanos);
        if (elapsed == 0L) return;
        tokens = Math.min(burst, tokens + elapsed * refillPerNano);
        lastRefillNanos = now;
    }

    record Decision(boolean allowed, int limit, int remaining, Duration retryAfter) {
        static Decision unlimited() {
            return new Decision(true, 0, 0, Duration.ZERO);
        }
    }
}
