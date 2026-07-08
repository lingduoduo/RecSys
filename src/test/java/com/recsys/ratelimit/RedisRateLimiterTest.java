package com.recsys.ratelimit;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    /** A RedisExecutor whose execute() applies the lambda against the given commands mock. */
    @SuppressWarnings("unchecked")
    private static RedisExecutor execFor(RedisCommands<String, String> cmd) {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(i ->
                i.getArgument(0, Function.class).apply(cmd));
        when(exec.executeRead(any())).thenAnswer(i ->
                i.getArgument(0, Function.class).apply(cmd));
        return exec;
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> mockCommands() {
        return mock(RedisCommands.class);
    }

    @Test
    void disabledLimiter_allowsRequests() {
        RedisRateLimiter limiter = RedisRateLimiter.disabled();

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(limiter.isEnabled()).isFalse();
    }

    @Test
    void tryAcquire_parsesAllowedRedisDecision() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 1L));
        RedisExecutor exec = execFor(cmd);
        // localPassFraction=0.0 forces every call to Redis, testing the parsing path
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1, 0.0);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(99L);
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
        assertThat(decision.failOpen()).isFalse();
    }

    @Test
    void tryAcquire_parsesRejectedRedisDecision() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(0L, 0L, 1L));
        RedisExecutor exec = execFor(cmd);
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1, 0.0);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("features");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void tryAcquire_failsOpenWhenRedisUnavailable() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new IllegalStateException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1, 0.0);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.failOpen()).isTrue();
    }

    @Test
    void tryAcquire_localPreCheck_skipsRedisWhenBelowThreshold() {
        RedisExecutor exec = mock(RedisExecutor.class);
        // limit=10, localPassFraction=0.7 → localPassThreshold=7
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 10L, 1, 0.7);

        // First 7 calls must be served locally without touching Redis
        for (int i = 0; i < 7; i++) {
            RedisRateLimiter.Decision d = limiter.tryAcquire("online");
            assertThat(d.allowed()).isTrue();
            assertThat(d.failOpen()).isFalse();
        }
        verify(exec, never()).execute(any());

        // Snapshot must expose the local threshold
        assertThat(limiter.localPassThreshold()).isEqualTo(7L);
    }

    @Test
    void tryAcquire_localPreCheck_fallsBackToRedisAboveThreshold() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 2L, 1L));
        RedisExecutor exec = execFor(cmd);
        // limit=5, localPassFraction=0.6 → localPassThreshold=3.
        // Fixed clock pins the local window so it can't roll over mid-test (the flake:
        // a wall-clock second boundary between calls reset localCount, letting the 4th
        // call pass locally instead of hitting Redis).
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 5L, 1, 0.6, () -> 1_000_000L);

        for (int i = 0; i < 3; i++) limiter.tryAcquire("online"); // exhaust local threshold
        limiter.tryAcquire("online"); // 4th call must hit Redis

        verify(exec).execute(any());
    }

    // ── Circuit breaker tests (缓存雪崩/限流降级) ────────────────────────────

    @Test
    void circuitBreaker_startsInClosedState() {
        RedisExecutor exec = mock(RedisExecutor.class);
        // failureThreshold=3, resetWindowMs=10_000
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 0.0, 3, 10_000L);

        assertThat(limiter.circuitState())
                .isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
    }

    @Test
    void circuitBreaker_opensAfterConsecutiveFailureThreshold() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 0.0, 3, 10_000L);

        for (int i = 0; i < 3; i++) limiter.tryAcquire("x");

        assertThat(limiter.circuitState())
                .isEqualTo(RedisRateLimiter.CircuitState.OPEN);
    }

    @Test
    void circuitBreaker_failsOpenWithoutRedisCallWhenOpen() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        // threshold=1 so a single failure opens the circuit
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 0.0, 1, 10_000L);

        limiter.tryAcquire("x"); // opens circuit (1 failure)

        // All subsequent calls must be fail-open without touching Redis.
        RedisRateLimiter.Decision d = limiter.tryAcquire("x");
        assertThat(d.allowed()).isTrue();
        assertThat(d.failOpen()).isTrue();
        // Only the first call hit Redis.
        verify(exec, times(1)).execute(any());
    }

    @Test
    void circuitBreaker_halfOpenAfterResetWindow() throws Exception {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        // threshold=1, resetWindowMs=20ms (very short for testing)
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 0.0, 1, 20L);

        limiter.tryAcquire("x"); // opens circuit
        Thread.sleep(30);       // wait past reset window

        assertThat(limiter.circuitState())
                .isEqualTo(RedisRateLimiter.CircuitState.HALF_OPEN);
    }

    @Test
    void circuitBreaker_closesOnSuccessfulProbeInHalfOpen() throws Exception {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 1L));
        RedisExecutor exec = mock(RedisExecutor.class);
        // First call throws (opens circuit); all subsequent calls apply the lambda (Redis recovered).
        // Chained stubs avoid re-stubbing after thenThrow, which would re-fire the exception.
        when(exec.execute(any()))
                .thenThrow(new RuntimeException("redis down"))
                .thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 0.0, 1, 20L);

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
        RedisExecutor exec = mock(RedisExecutor.class);
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 0.7, 5, 30_000L);

        RedisRateLimiter.Snapshot snap = limiter.snapshot();

        assertThat(snap.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
        assertThat(snap.enabled()).isTrue();
        assertThat(snap.limit()).isEqualTo(100L);
    }
}
