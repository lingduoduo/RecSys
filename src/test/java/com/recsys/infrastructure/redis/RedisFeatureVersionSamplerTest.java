package com.recsys.infrastructure.redis;

import com.recsys.metrics.ConsistencyMetrics;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code redis_feature_version_age_seconds} starts at zero and only moves on a successful
 * sample, so on its own a fresh process and a genuinely fresh feature view are the same
 * reading — and the freshness alert would read "0 seconds old" as healthy while the sampler
 * had in fact never once reached Redis. These tests pin the companion availability signal that
 * separates the two, for each way a sample can fail to produce a value.
 */
class RedisFeatureVersionSamplerTest {

    private static final String AVAILABLE = "redis_feature_version_sample_available";
    private static final String AGE = "redis_feature_version_age_seconds";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ConsistencyMetrics metrics = new ConsistencyMetrics(registry);

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    /** Fixed clock so the computed age is exact rather than "about ten seconds". */
    private static Clock clockAtMillis(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> commandsReturning(List<String> keys) {
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        KeyScanCursor<String> cursor = mock(KeyScanCursor.class);
        when(cursor.getKeys()).thenReturn(keys);
        when(commands.scan(any(ScanArgs.class))).thenReturn(cursor);
        return commands;
    }

    @SuppressWarnings("unchecked")
    private static RedisExecutor executorRunning(RedisCommands<String, String> commands) {
        RedisExecutor executor = mock(RedisExecutor.class);
        when(executor.executePrimaryRead(any()))
                .thenAnswer(invocation -> invocation.getArgument(0, Function.class).apply(commands));
        return executor;
    }

    @Test
    void validTimestampMarksSampleAvailable() {
        RedisCommands<String, String> commands = commandsReturning(List.of("user:7:updated_at"));
        when(commands.get("user:7:updated_at")).thenReturn("1000");

        boolean sampled = new RedisFeatureVersionSampler(
                executorRunning(commands), metrics, clockAtMillis(11_000), 100).sample();

        assertThat(sampled).isTrue();
        assertThat(gauge(AVAILABLE)).isOne();
        assertThat(gauge(AGE)).isEqualTo(10d);
        assertThat(gauge("redis_feature_version_max")).isEqualTo(1000d);
    }

    @Test
    void emptyScanMarksSampleUnavailable() {
        RedisCommands<String, String> commands = commandsReturning(List.of());

        boolean sampled = new RedisFeatureVersionSampler(
                executorRunning(commands), metrics, clockAtMillis(11_000), 100).sample();

        assertThat(sampled)
                .as("nothing matched *:updated_at, so there is no feature view to age")
                .isFalse();
        assertThat(gauge(AVAILABLE)).isZero();
    }

    /**
     * A key that exists but holds an unparseable value leaves the same hole as an empty scan:
     * no version was read, so nothing may claim the age gauge is current.
     */
    @Test
    void unparseableValuesMarkSampleUnavailable() {
        RedisCommands<String, String> commands = commandsReturning(List.of("user:7:updated_at"));
        when(commands.get("user:7:updated_at")).thenReturn("not-a-timestamp");

        boolean sampled = new RedisFeatureVersionSampler(
                executorRunning(commands), metrics, clockAtMillis(11_000), 100).sample();

        assertThat(sampled).isFalse();
        assertThat(gauge(AVAILABLE)).isZero();
    }

    @Test
    void redisFailureMarksSampleUnavailableAndRethrows() {
        RedisExecutor executor = mock(RedisExecutor.class);
        RuntimeException failure = new IllegalStateException("redis down");
        when(executor.executePrimaryRead(any())).thenThrow(failure);

        RedisFeatureVersionSampler sampler =
                new RedisFeatureVersionSampler(executor, metrics, clockAtMillis(11_000), 100);

        assertThatThrownBy(sampler::sample)
                .as("the scheduler decides whether to swallow a failure; sample() must not "
                        + "hide it from a direct caller")
                .isSameAs(failure);
        assertThat(gauge(AVAILABLE)).isZero();
    }

    /**
     * The alert reads age and availability as independent signals, so a failure after a good
     * sample must flip availability without rewriting the last age we could actually observe.
     */
    @Test
    void failureAfterAGoodSamplePreservesTheLastObservedAge() {
        RedisCommands<String, String> commands = commandsReturning(List.of("user:7:updated_at"));
        when(commands.get(anyString())).thenReturn("1000");
        RedisExecutor executor = executorRunning(commands);

        new RedisFeatureVersionSampler(executor, metrics, clockAtMillis(11_000), 100).sample();
        assertThat(gauge(AVAILABLE)).isOne();

        RedisExecutor broken = mock(RedisExecutor.class);
        when(broken.executePrimaryRead(any())).thenThrow(new IllegalStateException("redis down"));
        assertThatThrownBy(new RedisFeatureVersionSampler(broken, metrics, clockAtMillis(99_000), 100)::sample)
                .isInstanceOf(IllegalStateException.class);

        assertThat(gauge(AVAILABLE)).isZero();
        assertThat(gauge(AGE))
                .as("the last known-good age is the operator's only clue how stale the view was")
                .isEqualTo(10d);
    }
}
