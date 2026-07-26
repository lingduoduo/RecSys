package com.recsys.ratelimit;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    private static RedisExecutor failingRedis(String message) {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new IllegalStateException(message));
        return exec;
    }

    private static RedisRateLimiter limiter(RedisExecutor exec, long limit, int windowSeconds,
                                            double emergencyRatePerSecond, int emergencyBurst,
                                            java.util.function.LongSupplier tickerNanos) {
        return new RedisRateLimiter(exec, "rate:test:", limit, windowSeconds,
                100, 10_000L, emergencyRatePerSecond, emergencyBurst, tickerNanos, () -> 0L);
    }

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

    @Test
    void redisFailureUsesBoundedEmergencyBudget() {
        AtomicLong ticker = new AtomicLong();
        RedisExecutor exec = failingRedis("redis down");
        RedisRateLimiter limiter = limiter(exec, 100L, 1, 2.0, 2, ticker::get);

        assertThat(limiter.tryAcquire("online").allowed()).isTrue();
        assertThat(limiter.tryAcquire("online").allowed()).isTrue();
        RedisRateLimiter.Decision rejected = limiter.tryAcquire("online");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.failOpen()).isTrue();
        assertThat(rejected.source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
        assertThat(rejected.retryAfterSeconds()).isPositive();
    }

    @Test
    void emergencyBudgetRefillsFromInjectedMonotonicTicker() {
        AtomicLong ticker = new AtomicLong();
        RedisRateLimiter limiter = limiter(failingRedis("redis down"), 100L, 1, 2.0, 2, ticker::get);

        limiter.tryAcquire("online");
        limiter.tryAcquire("online");
        assertThat(limiter.tryAcquire("online").allowed()).isFalse();

        ticker.set(500_000_000L);
        assertThat(limiter.tryAcquire("online").allowed()).isTrue();
        assertThat(limiter.tryAcquire("online").allowed()).isFalse();
    }

    @Test
    void redisDecisionsAreAuthoritativeAndLeaveEmergencyBudgetUntouched() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L));
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any()))
                .thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd))
                .thenThrow(new IllegalStateException("redis down"));
        RedisRateLimiter limiter = limiter(exec, 100L, 1, 1.0, 2, () -> 0L);

        RedisRateLimiter.Decision redis = limiter.tryAcquire("online");
        assertThat(redis.allowed()).isTrue();
        assertThat(redis.source()).isEqualTo(RedisRateLimiter.Source.REDIS);

        assertThat(limiter.tryAcquire("online").allowed()).isTrue();
        assertThat(limiter.tryAcquire("online").allowed()).isTrue();
    }

    @Test
    void disabledGlobalLimiterReportsDisabledSource() {
        RedisRateLimiter.Decision decision = RedisRateLimiter.disabled().tryAcquire("online");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.source()).isEqualTo(RedisRateLimiter.Source.DISABLED);
    }

    @Test
    void zeroEmergencyRateExplicitlyRestoresUnlimitedFailOpen() {
        RedisRateLimiter limiter = limiter(failingRedis("redis down"), 100L, 1, 0.0, 2, () -> 0L);

        for (int i = 0; i < 4; i++) {
            RedisRateLimiter.Decision decision = limiter.tryAcquire("online");
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
        }
    }

    @Test
    void openCircuitSkipsRedisAndConsumesEmergencyBudget() {
        RedisExecutor exec = failingRedis("redis down");
        RedisRateLimiter limiter = new RedisRateLimiter(exec, "rate:test:", 100L, 1,
                1, 10_000L, 1.0, 2, () -> 0L, () -> 0L);

        assertThat(limiter.tryAcquire("online").allowed()).isTrue();
        assertThat(limiter.tryAcquire("online").allowed()).isTrue();
        assertThat(limiter.tryAcquire("online").allowed()).isFalse();
        verify(exec, times(1)).execute(any());
    }

    @Test
    void malformedRedisResultUsesEmergencyBudgetInsteadOfUnlimitedAllow() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of("not-an-allow-flag"));
        RedisRateLimiter limiter = limiter(execFor(cmd), 100L, 1, 1.0, 2, () -> 0L);

        assertThat(limiter.tryAcquire("online").source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
        assertThat(limiter.tryAcquire("online").allowed()).isTrue();
        assertThat(limiter.tryAcquire("online").allowed()).isFalse();
    }

    @Test
    void fractionalRedisReplyIsMalformedAndUsesEmergencyBudget() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1.5d, 99L, 0L));
        RedisRateLimiter limiter = limiter(execFor(cmd), 100L, 1, 1.0, 1, () -> 0L);

        RedisRateLimiter.Decision decision = limiter.tryAcquire("online");

        assertThat(decision.source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
        assertThat(decision.failOpen()).isTrue();
    }

    @Test
    void redisReplyWithExtraFieldsUsesEmergencyBudget() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L, 42L));
        RedisRateLimiter limiter = limiter(execFor(cmd), 100L, 1, 1.0, 1, () -> 0L);

        assertThat(limiter.tryAcquire("online").source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
    }

    @Test
    void allowedRedisReplyWithRetryUsesEmergencyBudget() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 5L));
        RedisRateLimiter limiter = limiter(execFor(cmd), 100L, 1, 1.0, 1, () -> 0L);

        assertThat(limiter.tryAcquire("online").source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
    }

    @Test
    void rejectedRedisReplyWithoutRetryUsesEmergencyBudget() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(0L, 0L, 0L));
        RedisRateLimiter limiter = limiter(execFor(cmd), 100L, 1, 1.0, 1, () -> 0L);

        assertThat(limiter.tryAcquire("online").source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
    }

    @Test
    void rejectedRedisReplyWithRemainingCapacityUsesEmergencyBudget() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(0L, 1L, 1L));
        RedisRateLimiter limiter = limiter(execFor(cmd), 100L, 1, 1.0, 1, () -> 0L);

        assertThat(limiter.tryAcquire("online").source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
    }

    @Test
    void allowedRedisReplyWithImpossibleRemainingUsesEmergencyBudget() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 100L, 0L));
        RedisRateLimiter limiter = limiter(execFor(cmd), 100L, 1, 1.0, 1, () -> 0L);

        assertThat(limiter.tryAcquire("online").source()).isEqualTo(RedisRateLimiter.Source.EMERGENCY);
    }

    @Test
    void negativeEmergencySettingsAreRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new RedisRateLimiter(
                mock(RedisExecutor.class), "rate:test:", 100L, 1,
                1, 1L, -1.0, 1, () -> 0L, () -> 0L)))
                .isInstanceOf(IllegalArgumentException.class);
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
    void circuitBreaker_halfOpenAfterResetWindow() {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenThrow(new RuntimeException("redis down"));
        AtomicLong clock = new AtomicLong();
        RedisRateLimiter limiter = new RedisRateLimiter(
                exec, "rate:", 100L, 1, 1, 20L, clock::get);

        limiter.tryAcquire("x");
        clock.set(20L);

        assertThat(limiter.circuitState()).isEqualTo(RedisRateLimiter.CircuitState.HALF_OPEN);
    }

    @Test
    void circuitBreaker_closesOnSuccessfulProbeInHalfOpen() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L));
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any()))
                .thenThrow(new RuntimeException("redis down"))
                .thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        AtomicLong clock = new AtomicLong();
        RedisRateLimiter limiter = new RedisRateLimiter(
                exec, "rate:", 100L, 1, 1, 20L, clock::get);

        limiter.tryAcquire("x"); // opens
        clock.set(20L);          // → HALF_OPEN

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

    @Test
    void registerMetrics_exposesOnlyFourBoundedDecisionOutcomesExactlyOnce() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L))
                .thenReturn(List.of(0L, 0L, 1L));
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any()))
                .thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd))
                .thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd))
                .thenThrow(new IllegalStateException("redis down"));
        RedisRateLimiter limiter = limiter(exec, 100L, 1, 1.0, 1, () -> 0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        limiter.registerMetrics(registry);
        limiter.registerMetrics(registry);
        assertThat(limiter.tryAcquire("user:one").allowed()).isTrue();
        assertThat(limiter.tryAcquire("user:two").allowed()).isFalse();
        assertThat(limiter.tryAcquire("user:three").allowed()).isTrue();
        assertThat(limiter.tryAcquire("user:four").allowed()).isFalse();

        List<Meter> meters = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName()
                        .equals("recsys_online_rate_limit_decisions_total"))
                .toList();
        assertThat(meters).hasSize(4);
        assertThat(meters).allSatisfy(meter -> assertThat(
                        meter.getId().getTags().stream()
                                .map(tag -> tag.getKey())
                                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("source", "result")));
        assertThat(registry.get("recsys_online_rate_limit_decisions_total")
                .tags("source", "redis", "result", "allowed").functionCounter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("recsys_online_rate_limit_decisions_total")
                .tags("source", "redis", "result", "rejected").functionCounter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("recsys_online_rate_limit_decisions_total")
                .tags("source", "emergency", "result", "allowed").functionCounter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("recsys_online_rate_limit_decisions_total")
                .tags("source", "emergency", "result", "rejected").functionCounter().count())
                .isEqualTo(1.0);
    }

    @Test
    void snapshot_exposesEmergencyConfigurationAndCumulativeDecisionCounters() {
        RedisCommands<String, String> cmd = mockCommands();
        when(cmd.eval(any(String.class), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
                .thenReturn(List.of(1L, 99L, 0L));
        RedisRateLimiter limiter = limiter(execFor(cmd), 100L, 1, 2.5, 3, () -> 0L);

        limiter.tryAcquire("online");
        RedisRateLimiter.Snapshot snapshot = limiter.snapshot();

        assertThat(snapshot.emergencyEnabled()).isTrue();
        assertThat(snapshot.emergencyRatePerSecond()).isEqualTo(2.5);
        assertThat(snapshot.emergencyBurst()).isEqualTo(3);
        assertThat(snapshot.redisAllowed()).isEqualTo(1L);
        assertThat(snapshot.redisRejected()).isZero();
        assertThat(snapshot.emergencyAllowed()).isZero();
        assertThat(snapshot.emergencyRejected()).isZero();
    }
}
