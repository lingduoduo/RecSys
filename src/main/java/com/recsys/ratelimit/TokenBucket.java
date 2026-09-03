package com.recsys.ratelimit;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class TokenBucket {
    private final double refillPerNano;
    private final int burst;
    private final LongSupplier tickerNanos;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double ratePerSecond, int burst, LongSupplier tickerNanos) {
        this.refillPerNano = ratePerSecond / TimeUnit.SECONDS.toNanos(1);
        this.burst = burst;
        this.tickerNanos = tickerNanos;
        this.tokens = burst;
        this.lastRefillNanos = tickerNanos.getAsLong();
    }

    public synchronized Decision tryAcquire(int needed) {
        refill();
        if (tokens >= needed) {
            tokens -= needed;
            return new Decision(true, burst, (int) Math.floor(tokens), Duration.ZERO);
        }
        long waitNanos = (long) Math.ceil((needed - tokens) / refillPerNano);
        return new Decision(false, burst, (int) Math.floor(tokens),
                Duration.ofNanos(Math.max(0L, waitNanos)));
    }

    public synchronized Decision tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Settles an estimated charge against what was actually used.
     *
     * <p>Refunds the unused remainder, or debits the overage when the estimate was too low. The
     * balance is deliberately allowed to go <em>negative</em> on an overage: that is what makes an
     * under-estimate self-correcting rather than free, since the deficit must refill before the
     * next request is admitted. A refund can never push the balance above {@code burst}, so
     * repeated over-estimates cannot mint capacity.
     */
    public synchronized void reconcile(int estimated, int actual) {
        refill();
        tokens = Math.min(burst, tokens + (estimated - actual));
    }

    private void refill() {
        long now = tickerNanos.getAsLong();
        long elapsed = Math.max(0L, now - lastRefillNanos);
        if (elapsed == 0L) return;
        tokens = Math.min(burst, tokens + elapsed * refillPerNano);
        lastRefillNanos = now;
    }

    public record Decision(boolean allowed, int limit, int remaining, Duration retryAfter) {
        static Decision unlimited() {
            return new Decision(true, 0, 0, Duration.ZERO);
        }
    }
}
