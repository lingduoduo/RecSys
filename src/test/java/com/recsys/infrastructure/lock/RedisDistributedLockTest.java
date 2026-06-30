package com.recsys.infrastructure.lock;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

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

    private RedisExecutor exec;
    private RedisCommands<String, String> cmd;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        exec = mock(RedisExecutor.class);
        cmd  = mock(RedisCommands.class);
        when(exec.execute(any())).thenAnswer(i ->
                i.getArgument(0, Function.class).apply(cmd));
        when(exec.executeRead(any())).thenAnswer(i ->
                i.getArgument(0, Function.class).apply(cmd));
    }

    // ── Approach 1: SETNX + EXPIRE (non-atomic) ────────────────────────────────────

    @Test
    void acquireNaive_issuesSetnxAndExpireAsSeparateCommands() {
        when(cmd.setnx(anyString(), anyString())).thenReturn(true);

        RedisDistributedLock lock = new RedisDistributedLock(exec, "dlock:", 30L);
        String token = lock.acquireNaive("resource:1");

        assertThat(token).isNotNull();
        // Two distinct Redis round-trips — this is the non-atomicity risk.
        verify(cmd).setnx(eq("dlock:resource:1"), eq(token));
        verify(cmd).expire(eq("dlock:resource:1"), eq(30L));
    }

    @Test
    void acquireNaive_returnsNullWhenSetnxFails() {
        when(cmd.setnx(anyString(), anyString())).thenReturn(false); // lock already held

        RedisDistributedLock lock = new RedisDistributedLock(exec, "dlock:", 30L);
        String token = lock.acquireNaive("resource:1");

        assertThat(token).isNull();
        verify(cmd, never()).expire(anyString(), anyLong()); // expire never called
    }

    @Test
    void acquireNaive_neverCallsEval_confirmingNonAtomicApproach() {
        when(cmd.setnx(anyString(), anyString())).thenReturn(true);

        new RedisDistributedLock(exec, "dlock:", 30L).acquireNaive("resource:1");

        verify(cmd, never()).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
        verify(cmd, never()).set(anyString(), anyString(), any(SetArgs.class));
    }

    // ── Approach 2: Lua script (atomic SET NX PX) ────────────────────────────────

    @Test
    void acquireWithLua_issuesSingleEvalCall_closingAtomicityGap() {
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(1L);

        RedisDistributedLock lock = new RedisDistributedLock(exec, "dlock:", 30L);
        String token = lock.acquireWithLua("resource:2");

        assertThat(token).isNotNull();
        // Exactly one Redis call — no separate SETNX or EXPIRE.
        verify(cmd, times(1)).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
        verify(cmd, never()).setnx(anyString(), anyString());
        verify(cmd, never()).expire(anyString(), anyLong());
    }

    @Test
    void acquireWithLua_passesOptimizedSetNxPxScriptAndMillisecondTtl() {
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(1L);

        RedisDistributedLock lock = new RedisDistributedLock(exec, "dlock:", 10L);
        lock.acquireWithLua("res");

        // values varargs are (token, "10000") — the second is the millisecond TTL.
        verify(cmd).eval(
                eq(RedisDistributedLock.ACQUIRE_LUA),
                eq(ScriptOutputType.INTEGER),
                eq(new String[]{"dlock:res"}),
                anyString(), eq("10000"));
        assertThat(RedisDistributedLock.ACQUIRE_LUA)
                .contains("'SET'", "'NX'", "'PX'")
                .doesNotContain("SETNX")
                .doesNotContain("EXPIRE");
    }

    @Test
    void acquireWithLua_returnsNullWhenEvalReturnsZero() {
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(0L);

        String token = new RedisDistributedLock(exec, "dlock:", 30L).acquireWithLua("res");

        assertThat(token).isNull();
    }

    // ── Approach 3: SET NX PX (single atomic command) ────────────────────────────

    @Test
    void acquire_issuesSingleSetNxPxCommand() {
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");

        RedisDistributedLock lock = new RedisDistributedLock(exec, "dlock:", 30L);
        String token = lock.acquire("resource:3");

        assertThat(token).isNotNull();
        verify(cmd, times(1)).set(
                eq("dlock:resource:3"), eq(token), any(SetArgs.class));
        // No eval(), no setnx(), no expire() — single-command approach.
        verify(cmd, never()).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
        verify(cmd, never()).setnx(anyString(), anyString());
        verify(cmd, never()).expire(anyString(), anyLong());
    }

    @Test
    void acquire_returnsNullWhenSetNxExReturnsNull() {
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(null);

        String token = new RedisDistributedLock(exec, "dlock:", 30L).acquire("resource:3");

        assertThat(token).isNull();
    }

    @Test
    void acquire_producesUniqueTokensAcrossCallsToTheSameLock() {
        when(cmd.set(anyString(), anyString(), any(SetArgs.class)))
                .thenReturn(null)  // second attempt fails (lock held by first)
                .thenReturn("OK"); // first attempt succeeds

        RedisDistributedLock lock = new RedisDistributedLock(exec, "dlock:", 30L);
        String t1 = lock.acquire("hot-key");
        String t2 = lock.acquire("hot-key");

        // t1 is null (lock was held), t2 is a fresh token
        assertThat(t1).isNull();
        assertThat(t2).isNotNull().isNotBlank();
    }

    // ── Safe release (Lua fencing token) ─────────────────────────────────────────

    @Test
    void release_deletesKeyWhenTokenMatches() {
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(1L);

        RedisDistributedLock lock = new RedisDistributedLock(exec, "dlock:", 30L);
        boolean released = lock.release("resource:1", "my-token");

        assertThat(released).isTrue();
        verify(cmd).eval(
                eq(RedisDistributedLock.RELEASE_LUA),
                eq(ScriptOutputType.INTEGER),
                eq(new String[]{"dlock:resource:1"}),
                eq("my-token"));
    }

    @Test
    void release_returnsFalseOnTokenMismatch_preventingStaleRelease() {
        // Lua script returns 0 when stored token != caller's token.
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(0L);

        boolean released = new RedisDistributedLock(exec, "dlock:", 30L)
                .release("resource:1", "stale-token");

        assertThat(released).isFalse();
    }

    @Test
    void release_returnsFalseForBlankTokenWithoutCallingRedis() {
        boolean nullReleased = new RedisDistributedLock(exec, "dlock:", 30L)
                .release("resource:1", null);
        boolean blankReleased = new RedisDistributedLock(exec, "dlock:", 30L)
                .release("resource:1", " ");

        assertThat(nullReleased).isFalse();
        assertThat(blankReleased).isFalse();
        verifyNoInteractions(exec);
    }

    @Test
    void defaultTtlMillis_convertsConfiguredSeconds() {
        RedisDistributedLock lock = new RedisDistributedLock(exec, "dlock:", 30L);

        assertThat(lock.defaultTtlSeconds()).isEqualTo(30L);
        assertThat(lock.defaultTtlMillis()).isEqualTo(30_000L);
    }

    @Test
    void acquireNaive_and_acquire_produceDifferentTokensPerCall() {
        when(cmd.setnx(anyString(), anyString())).thenReturn(true);
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");
        RedisDistributedLock lock = new RedisDistributedLock(exec, "dlock:", 30L);

        String t1 = lock.acquireNaive("res");
        String t2 = lock.acquire("res");

        assertThat(t1).isNotEqualTo(t2); // each call generates a fresh UUID token
    }
}
