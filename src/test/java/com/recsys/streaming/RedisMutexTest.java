package com.recsys.streaming;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisMutexTest {

    @Test
    void tryAcquire_returnsTokenWhenLockIsFree() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");

        RedisMutex mutex = new RedisMutex(pool, "mutex:", 5L);
        String token = mutex.tryAcquire("user:1");

        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void tryAcquire_returnsNullWhenLockIsHeld() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        // Redis SET NX returns null when the key already exists.
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null);

        RedisMutex mutex = new RedisMutex(pool, "mutex:", 5L);
        String token = mutex.tryAcquire("user:1");

        assertThat(token).isNull();
    }

    @Test
    void release_returnsTrueWhenTokenMatches() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        // Lua script returns 1 when key was deleted.
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        RedisMutex mutex = new RedisMutex(pool, "mutex:", 5L);
        boolean released = mutex.release("user:1", "some-token");

        assertThat(released).isTrue();
    }

    @Test
    void release_returnsFalseWhenTokenDoesNotMatch() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        // Lua script returns 0 when token didn't match (already expired or wrong holder).
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        RedisMutex mutex = new RedisMutex(pool, "mutex:", 5L);
        boolean released = mutex.release("user:1", "stale-token");

        assertThat(released).isFalse();
    }

    @Test
    void withLock_executesActionWhenLockAcquired() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        RedisMutex mutex = new RedisMutex(pool, "mutex:", 5L);
        AtomicBoolean actionRan = new AtomicBoolean(false);

        String result = mutex.withLock("resource", () -> { actionRan.set(true); return "done"; },
                                       () -> "fallback");

        assertThat(actionRan.get()).isTrue();
        assertThat(result).isEqualTo("done");
        // release() must be called after the action.
        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    @Test
    void withLock_executesFallbackWhenLockNotAcquired() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null);

        RedisMutex mutex = new RedisMutex(pool, "mutex:", 5L);
        AtomicBoolean fallbackRan = new AtomicBoolean(false);

        String result = mutex.withLock("resource",
                () -> "action",
                () -> { fallbackRan.set(true); return "degraded"; });

        assertThat(fallbackRan.get()).isTrue();
        assertThat(result).isEqualTo("degraded");
        // No release call when lock was never acquired.
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    // ── Distributed-lock correctness verification (分布式锁验证) ─────────────────

    @Test
    void tryAcquire_usesSingleAtomicSetNxExCommand() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");

        new RedisMutex(pool, "mutex:", 5L).tryAcquire("resource");

        // Exactly one SET NX EX — no separate SETNX or EXPIRE (atomic by design).
        verify(jedis, times(1)).set(eq("mutex:resource"), anyString(), any(SetParams.class));
        verify(jedis, never()).setnx(anyString(), anyString());
        verify(jedis, never()).expire(anyString(), anyLong());
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void tryAcquire_producesUniqueTokensOnEachCall() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        RedisMutex mutex = new RedisMutex(pool, "mutex:", 5L);

        String t1 = mutex.tryAcquire("r");
        String t2 = mutex.tryAcquire("r");

        assertThat(t1).isNotEqualTo(t2); // each acquisition uses a fresh UUID
    }

    @Test
    void release_usesLuaFencingTokenScript() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        new RedisMutex(pool, "mutex:", 5L).release("resource", "tok");

        // Lua script receives the lock key and the caller's token.
        verify(jedis).eval(anyString(),
                eq(java.util.List.of("mutex:resource")),
                eq(java.util.List.of("tok")));
    }

    @Test
    void withLock_releasesLockEvenWhenActionThrows() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        RedisMutex mutex = new RedisMutex(pool, "mutex:", 5L);

        try {
            mutex.withLock("r", () -> { throw new RuntimeException("boom"); }, () -> null);
        } catch (RuntimeException ignored) {}

        // Even though the action threw, release (eval) must still be called.
        verify(jedis).eval(anyString(), anyList(), anyList());
    }
}
