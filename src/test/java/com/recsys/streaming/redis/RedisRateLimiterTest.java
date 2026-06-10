package com.recsys.streaming.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.Pool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    @Test
    void disabledLimiter_allowsRequests() {
        RedisRateLimiter limiter = RedisRateLimiter.disabled();

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(limiter.isEnabled()).isFalse();
    }

    @Test
    void tryAcquire_parsesAllowedRedisDecision() {
        Jedis jedis = mock(Jedis.class);
        Pool<Jedis> pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of(1L, 99L, 1L));
        // localPassFraction=0.0 forces every call to Redis, testing the parsing path
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:test:", 100L, 1, 0.0);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(99L);
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
        assertThat(decision.failOpen()).isFalse();
    }

    @Test
    void tryAcquire_parsesRejectedRedisDecision() {
        Jedis jedis = mock(Jedis.class);
        Pool<Jedis> pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of(0L, 0L, 1L));
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:test:", 100L, 1, 0.0);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("features");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void tryAcquire_failsOpenWhenRedisUnavailable() {
        Pool<Jedis> pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new IllegalStateException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:test:", 100L, 1, 0.0);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.failOpen()).isTrue();
    }

    @Test
    void tryAcquire_localPreCheck_skipsRedisWhenBelowThreshold() {
        Jedis jedis = mock(Jedis.class);
        Pool<Jedis> pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        // limit=10, localPassFraction=0.7 → localPassThreshold=7
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:test:", 10L, 1, 0.7);

        // First 7 calls must be served locally without touching Redis
        for (int i = 0; i < 7; i++) {
            RedisRateLimiter.Decision d = limiter.tryAcquire("online");
            assertThat(d.allowed()).isTrue();
            assertThat(d.failOpen()).isFalse();
        }
        verify(jedis, never()).eval(anyString(), anyList(), anyList());

        // Snapshot must expose the local threshold
        assertThat(limiter.localPassThreshold()).isEqualTo(7L);
    }

    @Test
    void tryAcquire_localPreCheck_fallsBackToRedisAboveThreshold() {
        Jedis jedis = mock(Jedis.class);
        Pool<Jedis> pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of(1L, 2L, 1L));
        // limit=5, localPassFraction=0.6 → localPassThreshold=3
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:test:", 5L, 1, 0.6);

        for (int i = 0; i < 3; i++) limiter.tryAcquire("online"); // exhaust local threshold
        limiter.tryAcquire("online"); // 4th call must hit Redis

        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    // ── Circuit breaker tests (缓存雪崩/限流降级) ────────────────────────────

    @Test
    void circuitBreaker_startsInClosedState() {
        Pool<Jedis> pool = mock(JedisPool.class);
        // failureThreshold=3, resetWindowMs=10_000
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:", 100L, 1, 0.0, 3, 10_000L);

        assertThat(limiter.circuitState())
                .isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
    }

    @Test
    void circuitBreaker_opensAfterConsecutiveFailureThreshold() {
        Pool<Jedis> pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new RuntimeException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:", 100L, 1, 0.0, 3, 10_000L);

        for (int i = 0; i < 3; i++) limiter.tryAcquire("x");

        assertThat(limiter.circuitState())
                .isEqualTo(RedisRateLimiter.CircuitState.OPEN);
    }

    @Test
    void circuitBreaker_failsOpenWithoutRedisCallWhenOpen() {
        Pool<Jedis> pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new RuntimeException("redis down"));
        // threshold=1 so a single failure opens the circuit
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:", 100L, 1, 0.0, 1, 10_000L);

        limiter.tryAcquire("x"); // opens circuit (1 failure)

        // All subsequent calls must be fail-open without touching Redis.
        RedisRateLimiter.Decision d = limiter.tryAcquire("x");
        assertThat(d.allowed()).isTrue();
        assertThat(d.failOpen()).isTrue();
        // Only the first call hit Redis.
        verify(pool, times(1)).getResource();
    }

    @Test
    void circuitBreaker_halfOpenAfterResetWindow() throws Exception {
        Pool<Jedis> pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new RuntimeException("redis down"));
        // threshold=1, resetWindowMs=20ms (very short for testing)
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:", 100L, 1, 0.0, 1, 20L);

        limiter.tryAcquire("x"); // opens circuit
        Thread.sleep(30);       // wait past reset window

        assertThat(limiter.circuitState())
                .isEqualTo(RedisRateLimiter.CircuitState.HALF_OPEN);
    }

    @Test
    void circuitBreaker_closesOnSuccessfulProbeInHalfOpen() throws Exception {
        Jedis jedis = mock(Jedis.class);
        Pool<Jedis> pool = mock(JedisPool.class);
        // First call throws (opens circuit); all subsequent calls return jedis (Redis recovered).
        // Chained stubs avoid re-stubbing after thenThrow, which would re-fire the exception.
        when(pool.getResource())
                .thenThrow(new RuntimeException("redis down"))
                .thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of(1L, 99L, 1L));
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:", 100L, 1, 0.0, 1, 20L);

        limiter.tryAcquire("x"); // opens circuit (1st call throws)
        Thread.sleep(30);        // wait past reset window → HALF_OPEN

        RedisRateLimiter.Decision probe = limiter.tryAcquire("x"); // probe succeeds → CLOSED
        assertThat(probe.allowed()).isTrue();
        assertThat(probe.failOpen()).isFalse();
        assertThat(limiter.circuitState())
                .isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
    }

    @Test
    void snapshot_includesCircuitState() {
        Pool<Jedis> pool = mock(JedisPool.class);
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:", 100L, 1, 0.7, 5, 30_000L);

        RedisRateLimiter.Snapshot snap = limiter.snapshot();

        assertThat(snap.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
        assertThat(snap.enabled()).isTrue();
        assertThat(snap.limit()).isEqualTo(100L);
    }
}
