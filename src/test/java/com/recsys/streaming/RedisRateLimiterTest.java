package com.recsys.streaming;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        JedisPool pool = mock(JedisPool.class);
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
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of(0L, 0L, 1L));
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:test:", 100L, 1, 0.0);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("features");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void tryAcquire_failsOpenWhenRedisUnavailable() {
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new IllegalStateException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:test:", 100L, 1, 0.0);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.failOpen()).isTrue();
    }

    @Test
    void tryAcquire_localPreCheck_skipsRedisWhenBelowThreshold() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
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
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of(1L, 2L, 1L));
        // limit=5, localPassFraction=0.6 → localPassThreshold=3
        RedisRateLimiter limiter = new RedisRateLimiter(pool, "rate:test:", 5L, 1, 0.6);

        for (int i = 0; i < 3; i++) limiter.tryAcquire("online"); // exhaust local threshold
        limiter.tryAcquire("online"); // 4th call must hit Redis

        verify(jedis).eval(anyString(), anyList(), anyList());
    }
}
