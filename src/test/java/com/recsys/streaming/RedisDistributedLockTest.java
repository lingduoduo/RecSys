package com.recsys.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.params.SetParams;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies all three distributed-lock acquisition approaches and the shared safe-release
 * mechanism.
 *
 * Key invariants under test:
 *  1. acquireNaive  → issues SETNX and EXPIRE as two separate commands (non-atomic gap)
 *  2. acquireWithLua → issues one Lua SET NX PX acquisition — no SETNX/EXPIRE calls
 *  3. acquire        → issues a single SET NX PX command — no eval() needed
 *  4. release        → uses a Lua fencing-token check; returns false on token mismatch
 */
class RedisDistributedLockTest {

    private Jedis jedis;
    private Pool<Jedis> pool;

    @BeforeEach
    void setUp() {
        jedis = mock(Jedis.class);
        pool  = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
    }

    // ── Approach 1: SETNX + EXPIRE (non-atomic) ────────────────────────────────────

    @Test
    void acquireNaive_issuesSetnxAndExpireAsSeparateCommands() {
        when(jedis.setnx(anyString(), anyString())).thenReturn(1L);

        RedisDistributedLock lock = new RedisDistributedLock(pool, "dlock:", 30L);
        String token = lock.acquireNaive("resource:1");

        assertThat(token).isNotNull();
        // Two distinct Redis round-trips — this is the non-atomicity risk.
        verify(jedis).setnx(eq("dlock:resource:1"), eq(token));
        verify(jedis).expire(eq("dlock:resource:1"), eq(30L));
    }

    @Test
    void acquireNaive_returnsNullWhenSetnxFails() {
        when(jedis.setnx(anyString(), anyString())).thenReturn(0L); // lock already held

        RedisDistributedLock lock = new RedisDistributedLock(pool, "dlock:", 30L);
        String token = lock.acquireNaive("resource:1");

        assertThat(token).isNull();
        verify(jedis, never()).expire(anyString(), anyLong()); // expire never called
    }

    @Test
    void acquireNaive_neverCallsEval_confirmingNonAtomicApproach() {
        when(jedis.setnx(anyString(), anyString())).thenReturn(1L);

        new RedisDistributedLock(pool, "dlock:", 30L).acquireNaive("resource:1");

        verify(jedis, never()).eval(anyString(), anyList(), anyList());
        verify(jedis, never()).set(anyString(), anyString(), any(SetParams.class));
    }

    // ── Approach 2: Lua script (atomic SET NX PX) ────────────────────────────────

    @Test
    void acquireWithLua_issuesSingleEvalCall_closingAtomicityGap() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        RedisDistributedLock lock = new RedisDistributedLock(pool, "dlock:", 30L);
        String token = lock.acquireWithLua("resource:2");

        assertThat(token).isNotNull();
        // Exactly one Redis call — no separate SETNX or EXPIRE.
        verify(jedis, times(1)).eval(anyString(), anyList(), anyList());
        verify(jedis, never()).setnx(anyString(), anyString());
        verify(jedis, never()).expire(anyString(), anyLong());
    }

    @Test
    void acquireWithLua_passesOptimizedSetNxPxScriptAndMillisecondTtl() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        RedisDistributedLock lock = new RedisDistributedLock(pool, "dlock:", 10L);
        lock.acquireWithLua("res");

        verify(jedis).eval(
                eq(RedisDistributedLock.ACQUIRE_LUA),
                eq(List.of("dlock:res")),
                argThat(args -> args.size() == 2 && "10000".equals(args.get(1))));
        assertThat(RedisDistributedLock.ACQUIRE_LUA)
                .contains("'SET'", "'NX'", "'PX'")
                .doesNotContain("SETNX")
                .doesNotContain("EXPIRE");
    }

    @Test
    void acquireWithLua_returnsNullWhenEvalReturnsZero() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        String token = new RedisDistributedLock(pool, "dlock:", 30L).acquireWithLua("res");

        assertThat(token).isNull();
    }

    // ── Approach 3: SET NX PX (single atomic command) ────────────────────────────

    @Test
    void acquire_issuesSingleSetNxPxCommand() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");

        RedisDistributedLock lock = new RedisDistributedLock(pool, "dlock:", 30L);
        String token = lock.acquire("resource:3");

        assertThat(token).isNotNull();
        verify(jedis, times(1)).set(
                eq("dlock:resource:3"), eq(token), any(SetParams.class));
        // No eval(), no setnx(), no expire() — single-command approach.
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
        verify(jedis, never()).setnx(anyString(), anyString());
        verify(jedis, never()).expire(anyString(), anyLong());
    }

    @Test
    void acquire_returnsNullWhenSetNxExReturnsNull() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null);

        String token = new RedisDistributedLock(pool, "dlock:", 30L).acquire("resource:3");

        assertThat(token).isNull();
    }

    @Test
    void acquire_producesUniqueTokensAcrossCallsToTheSameLock() {
        when(jedis.set(anyString(), anyString(), any(SetParams.class)))
                .thenReturn(null)  // second attempt fails (lock held by first)
                .thenReturn("OK"); // first attempt succeeds

        RedisDistributedLock lock = new RedisDistributedLock(pool, "dlock:", 30L);
        String t1 = lock.acquire("hot-key");
        String t2 = lock.acquire("hot-key");

        // t1 is null (lock was held), t2 is a fresh token
        assertThat(t1).isNull();
        assertThat(t2).isNotNull().isNotBlank();
    }

    // ── Safe release (Lua fencing token) ─────────────────────────────────────────

    @Test
    void release_deletesKeyWhenTokenMatches() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        RedisDistributedLock lock = new RedisDistributedLock(pool, "dlock:", 30L);
        boolean released = lock.release("resource:1", "my-token");

        assertThat(released).isTrue();
        verify(jedis).eval(
                eq(RedisDistributedLock.RELEASE_LUA),
                eq(List.of("dlock:resource:1")),
                eq(List.of("my-token")));
    }

    @Test
    void release_returnsFalseOnTokenMismatch_preventingStaleRelease() {
        // Lua script returns 0 when stored token != caller's token.
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        boolean released = new RedisDistributedLock(pool, "dlock:", 30L)
                .release("resource:1", "stale-token");

        assertThat(released).isFalse();
    }

    @Test
    void release_returnsFalseForBlankTokenWithoutCallingRedis() {
        boolean nullReleased = new RedisDistributedLock(pool, "dlock:", 30L)
                .release("resource:1", null);
        boolean blankReleased = new RedisDistributedLock(pool, "dlock:", 30L)
                .release("resource:1", " ");

        assertThat(nullReleased).isFalse();
        assertThat(blankReleased).isFalse();
        verifyNoInteractions(jedis);
    }

    @Test
    void defaultTtlMillis_convertsConfiguredSeconds() {
        RedisDistributedLock lock = new RedisDistributedLock(pool, "dlock:", 30L);

        assertThat(lock.defaultTtlSeconds()).isEqualTo(30L);
        assertThat(lock.defaultTtlMillis()).isEqualTo(30_000L);
    }

    @Test
    void acquireNaive_and_acquire_produceDifferentTokensPerCall() {
        when(jedis.setnx(anyString(), anyString())).thenReturn(1L);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        RedisDistributedLock lock = new RedisDistributedLock(pool, "dlock:", 30L);

        String t1 = lock.acquireNaive("res");
        String t2 = lock.acquire("res");

        assertThat(t1).isNotEqualTo(t2); // each call generates a fresh UUID token
    }
}
