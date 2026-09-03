package com.recsys.ratelimit;
import com.recsys.ratelimit.TokenBucket;
import com.recsys.ratelimit.LlmTokenRateLimiter;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmTokenRateLimiterTest {

    @Test
    void disabledWhenNoEnvVarsSet() {
        LlmTokenRateLimiter limiter = LlmTokenRateLimiter.fromEnvironment(
                Map.<String, String>of()::get, System::nanoTime);

        assertFalse(limiter.isEnabled());
        assertTrue(limiter.tryAcquire(5000).allowed(), "disabled limiter must always allow");
    }

    @Test
    void consumesBurstThenBlocksUntilRefill() {
        AtomicLong now = new AtomicLong(0L);
        // 1000 tokens/s, burst of 2000
        LlmTokenRateLimiter limiter = LlmTokenRateLimiter.fromEnvironment(
                Map.of(
                        "LLM_TOKEN_RATE_LIMIT_TPS", "1000",
                        "LLM_TOKEN_RATE_LIMIT_BURST", "2000"
                )::get,
                now::get
        );

        assertTrue(limiter.isEnabled());
        // Consume all 2000 burst tokens in two requests
        assertTrue(limiter.tryAcquire(1000).allowed());
        assertTrue(limiter.tryAcquire(1000).allowed());
        // Bucket empty — next request should be denied
        assertFalse(limiter.tryAcquire(1).allowed());

        // Advance clock by 1 second to refill 1000 tokens
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(limiter.tryAcquire(1000).allowed());
        assertFalse(limiter.tryAcquire(1).allowed());
    }

    @Test
    void largeRequestBlocksWhenInsufficientTokens() {
        AtomicLong now = new AtomicLong(0L);
        LlmTokenRateLimiter limiter = LlmTokenRateLimiter.fromEnvironment(
                Map.of(
                        "LLM_TOKEN_RATE_LIMIT_TPS", "100",
                        "LLM_TOKEN_RATE_LIMIT_BURST", "500"
                )::get,
                now::get
        );

        // Consume 400 of 500 burst
        assertTrue(limiter.tryAcquire(400).allowed());
        // Request for 200 tokens — only 100 remaining, should be denied
        assertFalse(limiter.tryAcquire(200).allowed());
        // Request for exactly 100 tokens — should be allowed
        assertTrue(limiter.tryAcquire(100).allowed());
    }

    @Test
    void retryAfterReflectsRefillWait() {
        AtomicLong now = new AtomicLong(0L);
        LlmTokenRateLimiter limiter = LlmTokenRateLimiter.fromEnvironment(
                Map.of(
                        "LLM_TOKEN_RATE_LIMIT_TPS", "1000",
                        "LLM_TOKEN_RATE_LIMIT_BURST", "1000"
                )::get,
                now::get
        );

        limiter.tryAcquire(1000); // drain bucket
        TokenBucket.Decision denied = limiter.tryAcquire(500);
        assertFalse(denied.allowed());
        // 500 tokens at 1000 tokens/s = ~500 ms wait
        assertTrue(denied.retryAfter().toMillis() >= 400,
                "retryAfter should reflect ~500ms refill wait, got " + denied.retryAfter().toMillis() + "ms");
    }

    @Test
    void disabledInstanceAllowsWithoutEnvVars() {
        LlmTokenRateLimiter disabled = LlmTokenRateLimiter.disabled();
        assertFalse(disabled.isEnabled());
        assertTrue(disabled.tryAcquire(Integer.MAX_VALUE).allowed());
    }

    private static LlmTokenRateLimiter limiter(String tps, String burst, AtomicLong now) {
        return LlmTokenRateLimiter.fromEnvironment(
                Map.of("LLM_TOKEN_RATE_LIMIT_TPS", tps,
                       "LLM_TOKEN_RATE_LIMIT_BURST", burst)::get,
                now::get);
    }

    /**
     * The pre-check can only spend the caller's own declared {@code max_tokens}. Settling against
     * the real count is what stops an under-declared request from being nearly free.
     */
    @Test
    void anUnderEstimateIsChargedItsRealCostAndCanOverdrawTheBudget() {
        AtomicLong now = new AtomicLong(0L);
        LlmTokenRateLimiter limiter = limiter("1000", "2000", now);

        assertTrue(limiter.tryAcquire(10).allowed(), "a 10-token declaration is cheap");
        limiter.reconcile(10, 3000); // actually cost 3000 — more than the whole burst

        assertFalse(limiter.tryAcquire(1).allowed(),
                "the overage must leave the budget overdrawn, not merely empty");

        // 1 s of refill (1000 tokens) is not enough to clear a ~1010-token deficit.
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertFalse(limiter.tryAcquire(1).allowed(), "the deficit must refill before admitting more");

        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(limiter.tryAcquire(1).allowed(), "once the deficit is repaid, requests resume");
    }

    @Test
    void anOverEstimateIsRefunded() {
        AtomicLong now = new AtomicLong(0L);
        LlmTokenRateLimiter limiter = limiter("1000", "2000", now);

        assertTrue(limiter.tryAcquire(2000).allowed(), "spends the entire burst up front");
        assertFalse(limiter.tryAcquire(1).allowed());

        limiter.reconcile(2000, 100); // only 100 tokens were really used

        assertTrue(limiter.tryAcquire(1500).allowed(), "the unused 1900 tokens come back");
    }

    /**
     * Without a ceiling on the refund, a caller could declare a huge {@code max_tokens}, use
     * nothing, and mint capacity beyond the configured burst on every request.
     */
    @Test
    void aRefundCannotMintCapacityBeyondTheBurst() {
        AtomicLong now = new AtomicLong(0L);
        LlmTokenRateLimiter limiter = limiter("1000", "2000", now);

        assertTrue(limiter.tryAcquire(2000).allowed());
        limiter.reconcile(1_000_000, 0); // declared a million, used none

        assertTrue(limiter.tryAcquire(2000).allowed(), "the bucket refills to at most its burst");
        assertFalse(limiter.tryAcquire(1).allowed(),
                "and no further — the refund was capped at the burst, not added on top of it");
    }

    @Test
    void reconcileIsANoOpWhenTheLimiterIsDisabled() {
        LlmTokenRateLimiter limiter = LlmTokenRateLimiter.fromEnvironment(
                Map.<String, String>of()::get, System::nanoTime);

        limiter.reconcile(1, 1_000_000);

        assertTrue(limiter.tryAcquire(5000).allowed(), "a disabled limiter still allows everything");
    }
}
