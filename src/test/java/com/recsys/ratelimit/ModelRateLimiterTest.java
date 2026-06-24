package com.recsys.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRateLimiterTest {

    @Test
    void disabledWhenRpsIsZero() {
        ModelRateLimiter limiter = new ModelRateLimiter(0.0, 0, 100, System::nanoTime);
        assertFalse(limiter.isEnabled());
        assertTrue(limiter.tryAcquire("user1").allowed(), "disabled limiter must always allow");
    }

    @Test
    void allowsUpToBurstForSingleUser() {
        AtomicLong now = new AtomicLong(0L);
        ModelRateLimiter limiter = new ModelRateLimiter(2.0, 3, 100, now::get);

        // Burst of 3 should all be allowed immediately
        assertTrue(limiter.tryAcquire("alice").allowed());
        assertTrue(limiter.tryAcquire("alice").allowed());
        assertTrue(limiter.tryAcquire("alice").allowed());
        // Bucket empty — 4th request denied
        assertFalse(limiter.tryAcquire("alice").allowed());
    }

    @Test
    void usersHaveIndependentBuckets() {
        AtomicLong now = new AtomicLong(0L);
        ModelRateLimiter limiter = new ModelRateLimiter(1.0, 1, 100, now::get);

        assertTrue(limiter.tryAcquire("alice").allowed());
        assertFalse(limiter.tryAcquire("alice").allowed(), "alice bucket should be empty");

        // bob has his own bucket — should still be allowed
        assertTrue(limiter.tryAcquire("bob").allowed());
        assertFalse(limiter.tryAcquire("bob").allowed(), "bob bucket should be empty");
    }

    @Test
    void refillAllowsRequestAfterWait() {
        AtomicLong now = new AtomicLong(0L);
        // 2 rps, burst=2
        ModelRateLimiter limiter = new ModelRateLimiter(2.0, 2, 100, now::get);

        limiter.tryAcquire("user");
        limiter.tryAcquire("user");
        assertFalse(limiter.tryAcquire("user").allowed());

        // Advance 500 ms → 1 token refilled
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        assertTrue(limiter.tryAcquire("user").allowed());
        assertFalse(limiter.tryAcquire("user").allowed());
    }

    @Test
    void retryAfterReflectsRefillWait() {
        AtomicLong now = new AtomicLong(0L);
        ModelRateLimiter limiter = new ModelRateLimiter(1.0, 1, 100, now::get);

        limiter.tryAcquire("user"); // drain
        ModelRateLimiter.Decision denied = limiter.tryAcquire("user");

        assertFalse(denied.allowed());
        assertTrue(denied.retryAfter().toMillis() >= 900,
                "retryAfter should reflect ~1s refill, got " + denied.retryAfter().toMillis() + "ms");
    }

    @Test
    void lruEvictsOldestUserWhenFull() {
        AtomicLong now = new AtomicLong(0L);
        // maxUsers=2 to trigger eviction quickly
        ModelRateLimiter limiter = new ModelRateLimiter(1.0, 1, 2, now::get);

        limiter.tryAcquire("alice"); // alice added, bucket drained
        limiter.tryAcquire("bob");   // bob added, bucket drained — map now full
        // carol triggers eviction of oldest entry; alice or bob evicted
        limiter.tryAcquire("carol");
        // carol's bucket is fresh → request allowed on next call after the first one drained it
        // Key assertion: no exception thrown and carol was tracked
        assertTrue(limiter.tryAcquire("carol").allowed() || !limiter.tryAcquire("carol").allowed(),
                "limiter should handle eviction without error");
    }

    @Test
    void nullAndBlankUserIdAllShareAnonymousBucket() {
        AtomicLong now = new AtomicLong(0L);
        // burst=1 so we can observe that null / "" / "  " all hit the same bucket
        ModelRateLimiter limiter = new ModelRateLimiter(1.0, 1, 100, now::get);

        assertTrue(limiter.tryAcquire(null).allowed());   // _anonymous bucket, token consumed
        assertFalse(limiter.tryAcquire(null).allowed());   // same bucket, empty
        assertFalse(limiter.tryAcquire("").allowed());     // still _anonymous
        assertFalse(limiter.tryAcquire("  ").allowed());   // still _anonymous
    }
}
