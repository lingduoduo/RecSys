package com.recsys.infrastructure.lock;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisMutexTest {

    private RedisExecutor exec;
    @SuppressWarnings("unchecked")
    private RedisCommands<String, String> cmd = mock(RedisCommands.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        exec = mock(RedisExecutor.class);
        cmd = mock(RedisCommands.class);
        when(exec.execute(any())).thenAnswer(i ->
                i.getArgument(0, Function.class).apply(cmd));
        when(exec.executeRead(any())).thenAnswer(i ->
                i.getArgument(0, Function.class).apply(cmd));
    }

    @Test
    void tryAcquire_returnsTokenWhenLockIsFree() {
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");

        RedisMutex mutex = new RedisMutex(exec, "mutex:", 5L);
        String token = mutex.tryAcquire("user:1");

        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void tryAcquire_returnsNullWhenLockIsHeld() {
        // Redis SET NX returns null when the key already exists.
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(null);

        RedisMutex mutex = new RedisMutex(exec, "mutex:", 5L);
        String token = mutex.tryAcquire("user:1");

        assertThat(token).isNull();
    }

    @Test
    void release_returnsTrueWhenTokenMatches() {
        // Lua script returns 1 when key was deleted.
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(1L);

        RedisMutex mutex = new RedisMutex(exec, "mutex:", 5L);
        boolean released = mutex.release("user:1", "some-token");

        assertThat(released).isTrue();
    }

    @Test
    void release_returnsFalseWhenTokenDoesNotMatch() {
        // Lua script returns 0 when token didn't match (already expired or wrong holder).
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(0L);

        RedisMutex mutex = new RedisMutex(exec, "mutex:", 5L);
        boolean released = mutex.release("user:1", "stale-token");

        assertThat(released).isFalse();
    }

    @Test
    void withLock_executesActionWhenLockAcquired() {
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(1L);

        RedisMutex mutex = new RedisMutex(exec, "mutex:", 5L);
        AtomicBoolean actionRan = new AtomicBoolean(false);

        String result = mutex.withLock("resource", () -> { actionRan.set(true); return "done"; },
                                       () -> "fallback");

        assertThat(actionRan.get()).isTrue();
        assertThat(result).isEqualTo("done");
        // release() must be called after the action.
        verify(cmd).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
    }

    @Test
    void withLock_executesFallbackWhenLockNotAcquired() {
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(null);

        RedisMutex mutex = new RedisMutex(exec, "mutex:", 5L);
        AtomicBoolean fallbackRan = new AtomicBoolean(false);

        String result = mutex.withLock("resource",
                () -> "action",
                () -> { fallbackRan.set(true); return "degraded"; });

        assertThat(fallbackRan.get()).isTrue();
        assertThat(result).isEqualTo("degraded");
        // No release call when lock was never acquired.
        verify(cmd, never()).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
    }

    // ── Distributed-lock correctness verification (分布式锁验证) ─────────────────

    @Test
    void tryAcquire_usesSingleAtomicSetNxExCommand() {
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");

        new RedisMutex(exec, "mutex:", 5L).tryAcquire("resource");

        // Exactly one SET NX EX — no separate SETNX or EXPIRE (atomic by design).
        verify(cmd, times(1)).set(eq("mutex:resource"), anyString(), any(SetArgs.class));
        verify(cmd, never()).setnx(anyString(), anyString());
        verify(cmd, never()).expire(anyString(), anyLong());
        verify(cmd, never()).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
    }

    @Test
    void tryAcquire_producesUniqueTokensOnEachCall() {
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");
        RedisMutex mutex = new RedisMutex(exec, "mutex:", 5L);

        String t1 = mutex.tryAcquire("r");
        String t2 = mutex.tryAcquire("r");

        assertThat(t1).isNotEqualTo(t2); // each acquisition uses a fresh UUID
    }

    @Test
    void release_usesLuaFencingTokenScript() {
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(1L);

        new RedisMutex(exec, "mutex:", 5L).release("resource", "tok");

        // Lua script receives the lock key and the caller's token.
        verify(cmd).eval(anyString(),
                eq(ScriptOutputType.INTEGER),
                eq(new String[]{"mutex:resource"}),
                eq("tok"));
    }

    @Test
    void withLock_releasesLockEvenWhenActionThrows() {
        when(cmd.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");
        when(cmd.eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(1L);
        RedisMutex mutex = new RedisMutex(exec, "mutex:", 5L);

        try {
            mutex.withLock("r", () -> { throw new RuntimeException("boom"); }, () -> null);
        } catch (RuntimeException ignored) {}

        // Even though the action threw, release (eval) must still be called.
        verify(cmd).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
    }
}
