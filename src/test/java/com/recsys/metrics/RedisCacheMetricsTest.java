package com.recsys.metrics;

import com.recsys.infrastructure.redis.RedisCacheStatsProbe.CacheStats;
import com.recsys.infrastructure.redis.RedisPersistentKeyProbe.KeyspaceSample;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCacheMetricsTest {

    private static CacheStats stats(long used, long evicted, long hits, long misses) {
        return new CacheStats(true, used, 209_715_200L, evicted, hits, misses, true);
    }

    @Test
    void publishesTheSampledMemoryAndKeyspaceValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheMetrics metrics = new RedisCacheMetrics(registry);

        metrics.update(stats(1_048_576L, 42L, 900L, 100L));

        assertThat(registry.get("redis_cache_used_memory_bytes").gauge().value()).isEqualTo(1_048_576d);
        assertThat(registry.get("redis_cache_max_memory_bytes").gauge().value()).isEqualTo(209_715_200d);
        assertThat(registry.get("redis_cache_evicted_keys").functionCounter().count()).isEqualTo(42d);
        assertThat(registry.get("redis_cache_keyspace_hits").functionCounter().count()).isEqualTo(900d);
        assertThat(registry.get("redis_cache_keyspace_misses").functionCounter().count()).isEqualTo(100d);
        assertThat(registry.get("redis_cache_available").gauge().value()).isEqualTo(1d);
    }

    @Test
    void flagsAnEvictionPolicyThatNoLongerProtectsKeysWithoutATtl() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheMetrics metrics = new RedisCacheMetrics(registry);

        metrics.update(new CacheStats(true, 1L, 2L, 0L, 0L, 0L, true));
        assertThat(registry.get("redis_cache_evicts_only_volatile_keys").gauge().value()).isEqualTo(1d);

        metrics.update(new CacheStats(true, 1L, 2L, 0L, 0L, 0L, false));
        assertThat(registry.get("redis_cache_evicts_only_volatile_keys").gauge().value()).isEqualTo(0d);
    }

    @Test
    void anUnavailableSampleNeverWalksTheCumulativeCountersBackwards() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheMetrics metrics = new RedisCacheMetrics(registry);
        metrics.update(stats(1_048_576L, 42L, 900L, 100L));

        metrics.update(CacheStats.unavailable());

        assertThat(registry.get("redis_cache_available").gauge().value())
                .as("availability must drop so the gap is visible")
                .isEqualTo(0d);
        assertThat(registry.get("redis_cache_evicted_keys").functionCounter().count())
                .as("a counter that resets to 0 reads as a Redis restart and corrupts rate()")
                .isEqualTo(42d);
        assertThat(registry.get("redis_cache_keyspace_hits").functionCounter().count()).isEqualTo(900d);
    }

    @Test
    void publishesTheKeyspaceSample() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheMetrics metrics = new RedisCacheMetrics(registry);

        metrics.updateKeyspace(new KeyspaceSample(true, 200, 2, java.util.List.of("leak:1", "leak:2")));

        assertThat(registry.get("redis_keyspace_sampled_keys").gauge().value()).isEqualTo(200d);
        assertThat(registry.get("redis_unexpected_persistent_keys").gauge().value()).isEqualTo(2d);
    }

    @Test
    void anUnavailableKeyspaceSampleDoesNotReportAFalseAllClear() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheMetrics metrics = new RedisCacheMetrics(registry);
        metrics.updateKeyspace(new KeyspaceSample(true, 200, 2, java.util.List.of("leak:1")));

        metrics.updateKeyspace(KeyspaceSample.unavailable());

        assertThat(registry.get("redis_unexpected_persistent_keys").gauge().value())
                .as("a failed scan must not look like the leak was fixed")
                .isEqualTo(2d);
    }
}
