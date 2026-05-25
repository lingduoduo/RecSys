package com.recsys.microservice;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Token-count-aware rate limiter for LLM routes.
 *
 * Unlike the per-request {@link GatewayRateLimiter}, this bucket consumes N tokens per call
 * where N is the LLM token estimate for the request (read from {@code max_tokens} in the
 * request body). This prevents a small number of large-context requests from exhausting a
 * shared downstream token quota.
 *
 * Env vars:
 *   LLM_TOKEN_RATE_LIMIT_TPS   — refill rate in LLM-tokens/second (0 = disabled)
 *   LLM_TOKEN_RATE_LIMIT_BURST — burst capacity in LLM-tokens (0 = disabled)
 */
final class LlmTokenRateLimiter {
    private static final LlmTokenRateLimiter DISABLED = new LlmTokenRateLimiter(null);

    private final TokenBucket bucket;

    private LlmTokenRateLimiter(TokenBucket bucket) {
        this.bucket = bucket;
    }

    static LlmTokenRateLimiter disabled() {
        return DISABLED;
    }

    static LlmTokenRateLimiter fromEnvironment() {
        return fromEnvironment(System::getenv, System::nanoTime);
    }

    static LlmTokenRateLimiter fromEnvironment(EnvReader env, LongSupplier tickerNanos) {
        double tps = readDouble(env, "LLM_TOKEN_RATE_LIMIT_TPS", 0.0);
        int burst = readInt(env, "LLM_TOKEN_RATE_LIMIT_BURST", 0);
        if (tps > 0.0 && burst > 0) {
            return new LlmTokenRateLimiter(new TokenBucket(tps, burst, tickerNanos));
        }
        return disabled();
    }

    boolean isEnabled() {
        return bucket != null;
    }

    /**
     * Try to consume {@code tokens} from the bucket.
     * @param tokens estimated LLM token count for this request (must be >= 1)
     */
    Decision tryAcquire(int tokens) {
        if (bucket == null) return Decision.unlimited();
        return bucket.tryAcquire(Math.max(1, tokens));
    }

    private static double readDouble(EnvReader env, String name, double def) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid decimal: " + raw);
        }
    }

    private static int readInt(EnvReader env, String name, int def) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid integer: " + raw);
        }
    }

    @FunctionalInterface
    interface EnvReader {
        String get(String name);
    }

    record Decision(boolean allowed, int limit, int remaining, Duration retryAfter) {
        static Decision unlimited() {
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

        private void refill() {
            long now = tickerNanos.getAsLong();
            long elapsed = Math.max(0L, now - lastRefillNanos);
            if (elapsed == 0L) return;
            tokens = Math.min(burst, tokens + elapsed * refillPerNano);
            lastRefillNanos = now;
        }
    }
}
