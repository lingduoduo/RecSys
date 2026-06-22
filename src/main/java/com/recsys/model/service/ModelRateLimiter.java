package com.recsys.model.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-user local token bucket rate limiter for the model inference endpoint.
 *
 * Prevents a single userId from monopolizing ONNX inference slots that are shared across
 * all active users. Each user gets an independent bucket that refills at {@code rps}
 * tokens/second with a maximum burst of {@code burst} tokens.
 *
 * The limiter tracks at most {@code maxUsers} buckets (access-ordered LRU). All callers
 * to {@link #tryAcquire} are tracked; the LRU cap bounds memory to at most maxUsers buckets.
 *
 * Controlled via Spring properties:
 *   recsys.model.rate-limit.rps        — per-user requests/sec (default 0 = disabled)
 *   recsys.model.rate-limit.burst      — burst capacity per user (default 0 = disabled)
 *   recsys.model.rate-limit.max-users  — max tracked users (default 10000)
 */
@Component
public class ModelRateLimiter {

    private final double ratePerSecond;
    private final int burstSize;
    private final boolean enabled;
    private final Map<String, TokenBucket> buckets;
    private final LongSupplier tickerNanos;

    @Autowired
    public ModelRateLimiter(
            @Value("${recsys.model.rate-limit.rps:0.0}") double rps,
            @Value("${recsys.model.rate-limit.burst:0}") int burst,
            @Value("${recsys.model.rate-limit.max-users:10000}") int maxUsers) {
        this(rps, burst, maxUsers, System::nanoTime);
    }

    ModelRateLimiter(double rps, int burst, int maxUsers, LongSupplier tickerNanos) {
        this.ratePerSecond = rps;
        this.burstSize = burst;
        this.enabled = rps > 0.0 && burst > 0;
        this.tickerNanos = tickerNanos;
        int cap = Math.max(1, maxUsers);
        this.buckets = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TokenBucket> eldest) {
                return size() > cap;
            }
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Attempt to acquire one token for the given userId.
     * Always returns {@link Decision#unlimited()} when disabled.
     */
    public Decision tryAcquire(String userId) {
        if (!enabled) return Decision.unlimited();
        String key = normalizeUserId(userId);
        // computeIfAbsent on a synchronizedMap is atomic — the LinkedHashMap's removeEldestEntry
        // is called inside the same lock, so LRU eviction is consistent.
        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(ratePerSecond, burstSize, tickerNanos));
        return bucket.tryAcquire();
    }

    private static String normalizeUserId(String userId) {
        return Strings.orDefault(userId, "_anonymous");
    }

    public record Decision(boolean allowed, int limit, int remaining, Duration retryAfter) {
        public static Decision unlimited() {
            return new Decision(true, 0, 0, Duration.ZERO);
        }
    }

    private static final class TokenBucket {
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

        synchronized Decision tryAcquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Decision(true, burst, (int) Math.floor(tokens), Duration.ZERO);
            }
            long waitNanos = (long) Math.ceil((1.0 - tokens) / refillPerNano);
            return new Decision(false, burst, 0, Duration.ofNanos(Math.max(0L, waitNanos)));
        }

        private void refill() {
            long now = tickerNanos.getAsLong();
            long elapsed = Math.max(0L, now - lastRefillNanos);
            if (elapsed == 0L) return;
            tokens = Math.min(burst, tokens + elapsed * refillPerNano);
            lastRefillNanos = now;
        }
    }
}
