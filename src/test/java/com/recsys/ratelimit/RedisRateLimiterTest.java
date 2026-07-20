package com.recsys.ratelimit;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    @SuppressWarnings("unchecked")
    private static RedisExecutor execFor(RedisCommands<String, String> cmd) {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
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
    void tryAcquire_consultsRedisOnEveryRequest_noLocalFastPath() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L));
        RedisExecutor exec = execFor(cmd);
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1);

        // Even the very first request must hit Redis — there is no local free budget.
        limiter.tryAcquire("online");
        limiter.tryAcquire("online");
        limiter.tryAcquire("online");
        verify(exec, times(3)).execute(any());
    }

    @Test
    void tryAcquire_parsesAllowedRedisDecision() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L));
        RedisRateLimiter limiter = new RedisRateLimiter(execFor(cmd), "rate:test:", 100L, 1);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(99L);
        assertThat(decision.retryAfterSeconds()).isEqualTo(0);
        assertThat(decision.failOpen()).isFalse();
    }

    @Test
    void tryAcquire_parsesRejectedRedisDecision() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(0L, 0L, 1L));
        RedisRateLimiter limiter = new RedisRateLimiter(execFor(cmd), "rate:test:", 100L, 1);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("features");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void tryAcquire_passesLimitWindowMsAndClockToScript() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 1L, 0L));
        RedisExecutor exec = execFor(cmd);
        // window=2s → windowMs=2000; fixed clock=1_234_000
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 50L, 2, () -> 1_234_000L);

        limiter.tryAcquire("online");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String[]> args = ArgumentCaptor.forClass(String[].class);
        verify(cmd).eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), args.capture());
        String[] argv = args.getValue();
        assertThat(argv[0]).isEqualTo("50");         // limit
        assertThat(argv[1]).isEqualTo("2000");       // windowMs
        assertThat(argv[2]).isEqualTo("1234000");    // nowMs
    }

    @Test
    void tryAcquire_failsOpenWhenRedisUnavailable() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new IllegalStateException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("recommendation");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.failOpen()).isTrue();
    }

    // ── Circuit breaker (cache-avalanche / rate-limit degradation) ──────────

    @Test
    void circuitBreaker_startsInClosedState() {
        RedisRateLimiter limiter = new RedisRateLimiter(
                mock(RedisExecutor.class), "rate:", 100L, 1, 3, 10_000L);
        assertThat(limiter.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
    }

    @Test
    void circuitBreaker_opensAfterConsecutiveFailureThreshold() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 3, 10_000L);

        for (int i = 0; i < 3; i++) limiter.tryAcquire("x");

        assertThat(limiter.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.OPEN);
    }

    @Test
    void circuitBreaker_failsOpenWithoutRedisCallWhenOpen() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 1, 10_000L);

        limiter.tryAcquire("x"); // opens circuit (1 failure)

        RedisRateLimiter.Decision d = limiter.tryAcquire("x");
        assertThat(d.allowed()).isTrue();
        assertThat(d.failOpen()).isTrue();
        verify(exec, times(1)).execute(any()); // only the first call hit Redis
    }

    @Test
    void circuitBreaker_halfOpenAfterResetWindow() throws Exception {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 1, 20L);

        limiter.tryAcquire("x");
        Thread.sleep(30);

        assertThat(limiter.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.HALF_OPEN);
    }

    @Test
    void circuitBreaker_closesOnSuccessfulProbeInHalfOpen() throws Exception {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L));
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any()))
                .thenThrow(new RuntimeException("redis down"))
                .thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:", 100L, 1, 1, 20L);

        limiter.tryAcquire("x"); // opens
        Thread.sleep(30);        // → HALF_OPEN

        RedisRateLimiter.Decision probe = limiter.tryAcquire("x"); // probe succeeds → CLOSED
        assertThat(probe.allowed()).isTrue();
        assertThat(probe.failOpen()).isFalse();
        assertThat(limiter.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
    }

    @Test
    void snapshot_hasNoLocalPassThreshold_andCarriesCircuitState() {
        RedisRateLimiter limiter = new RedisRateLimiter(
                mock(RedisExecutor.class), "rate:", 100L, 1, 5, 30_000L);

        RedisRateLimiter.Snapshot snap = limiter.snapshot();

        assertThat(snap.enabled()).isTrue();
        assertThat(snap.limit()).isEqualTo(100L);
        assertThat(snap.windowSeconds()).isEqualTo(1);
        assertThat(snap.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.CLOSED);
        // Snapshot must NOT expose a local-fast-path threshold anymore.
        assertThat(java.util.Arrays.stream(RedisRateLimiter.Snapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("localPassThreshold");
    }
}
