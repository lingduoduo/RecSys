package com.recsys.microservice;

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

}
