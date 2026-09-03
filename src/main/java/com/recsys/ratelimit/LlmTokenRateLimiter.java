package com.recsys.ratelimit;
import com.recsys.config.EnvVars;

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
public final class LlmTokenRateLimiter {
    private static final LlmTokenRateLimiter DISABLED = new LlmTokenRateLimiter(null);

    private final TokenBucket bucket;

    private LlmTokenRateLimiter(TokenBucket bucket) {
        this.bucket = bucket;
    }

    public static LlmTokenRateLimiter disabled() {
        return DISABLED;
    }

    public static LlmTokenRateLimiter fromEnvironment() {
        return fromEnvironment(System::getenv, System::nanoTime);
    }

    public static LlmTokenRateLimiter fromEnvironment(EnvVars.EnvReader env, LongSupplier tickerNanos) {
        double tps = EnvVars.readDouble(env, "LLM_TOKEN_RATE_LIMIT_TPS", 0.0);
        int burst = EnvVars.readInt(env, "LLM_TOKEN_RATE_LIMIT_BURST", 0);
        if (tps > 0.0 && burst > 0) {
            return new LlmTokenRateLimiter(new TokenBucket(tps, burst, tickerNanos));
        }
        return disabled();
    }

    public boolean isEnabled() {
        return bucket != null;
    }

    public TokenBucket.Decision tryAcquire(int tokens) {
        if (bucket == null) return TokenBucket.Decision.unlimited();
        return bucket.tryAcquire(Math.max(1, tokens));
    }

    /**
     * Settles the pre-checked estimate against the token count the upstream actually reported.
     *
     * <p>The pre-check can only spend the caller's own {@code max_tokens}, which the caller
     * controls and the gateway cannot verify before forwarding. Without this settle-up, declaring
     * {@code max_tokens: 1} and then consuming hundreds costs one token, and the budget stops
     * bounding anything a caller cares to under-declare.
     */
    public void reconcile(int estimated, int actual) {
        if (bucket == null) return;
        bucket.reconcile(Math.max(1, estimated), Math.max(0, actual));
    }
}
