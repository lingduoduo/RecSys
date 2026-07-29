package com.recsys.metrics;

import com.recsys.infrastructure.redis.RedisCacheStatsProbe.CacheStats;
import com.recsys.infrastructure.redis.RedisPersistentKeyProbe.KeyspaceSample;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes Redis's own cache counters, so eviction pressure stops being indistinguishable
 * from a Redis outage — both otherwise surface only as a rising application-side miss rate.
 *
 * <p>Cumulative counters are <em>retained</em> across an unavailable sample rather than
 * reset: a counter that walks backwards reads as a Redis restart downstream and corrupts
 * {@code rate()}. {@code redis_cache_available} is what goes to 0 during a gap.
 */
public final class RedisCacheMetrics {

    private final AtomicLong available = new AtomicLong();
    private final AtomicLong usedMemoryBytes = new AtomicLong();
    private final AtomicLong maxMemoryBytes = new AtomicLong();
    private final AtomicLong evictedKeys = new AtomicLong();
    private final AtomicLong keyspaceHits = new AtomicLong();
    private final AtomicLong keyspaceMisses = new AtomicLong();
    private final AtomicLong evictsOnlyVolatileKeys = new AtomicLong();
    private final AtomicLong keyspaceSampled = new AtomicLong();
    private final AtomicLong unexpectedPersistentKeys = new AtomicLong();
    private final AtomicLong keyspaceSampleAvailable = new AtomicLong();

    public RedisCacheMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Gauge.builder("redis_cache_available", available, AtomicLong::get)
                .description("1 when the last INFO sample succeeded, 0 while Redis is unreachable")
                .register(registry);
        Gauge.builder("redis_cache_used_memory_bytes", usedMemoryBytes, AtomicLong::get)
                .baseUnit("bytes").register(registry);
        Gauge.builder("redis_cache_max_memory_bytes", maxMemoryBytes, AtomicLong::get)
                .baseUnit("bytes")
                .description("Configured maxmemory; 0 means no limit")
                .register(registry);
        Gauge.builder("redis_cache_evicts_only_volatile_keys", evictsOnlyVolatileKeys, AtomicLong::get)
                .description("1 when the running maxmemory-policy cannot evict keys that have no TTL")
                .register(registry);
        FunctionCounter.builder("redis_cache_evicted_keys", evictedKeys, AtomicLong::get)
                .description("Keys evicted by Redis under memory pressure")
                .register(registry);
        FunctionCounter.builder("redis_cache_keyspace_hits", keyspaceHits, AtomicLong::get)
                .register(registry);
        FunctionCounter.builder("redis_cache_keyspace_misses", keyspaceMisses, AtomicLong::get)
                .register(registry);
        Gauge.builder("redis_keyspace_sampled_keys", keyspaceSampled, AtomicLong::get)
                .description("Keys examined by the most recent bounded keyspace sample")
                .register(registry);
        Gauge.builder("redis_unexpected_persistent_keys", unexpectedPersistentKeys, AtomicLong::get)
                .description("Sampled keys with no TTL that are not on the durable allow-list; "
                        + "under volatile-lru these can never be evicted")
                .register(registry);
        Gauge.builder("redis_keyspace_sample_available", keyspaceSampleAvailable, AtomicLong::get)
                .description("1 when the last keyspace sample succeeded, 0 otherwise; a probe that "
                        + "never succeeds would otherwise leave redis_unexpected_persistent_keys at "
                        + "its initial 0, indistinguishable from all-clear")
                .register(registry);
    }

    public void update(CacheStats stats) {
        Objects.requireNonNull(stats, "stats");
        available.set(stats.available() ? 1 : 0);
        if (!stats.available()) return; // keep the last-known values; only availability drops
        usedMemoryBytes.set(Math.max(0L, stats.usedMemoryBytes()));
        maxMemoryBytes.set(Math.max(0L, stats.maxMemoryBytes()));
        evictedKeys.set(Math.max(0L, stats.evictedKeys()));
        keyspaceHits.set(Math.max(0L, stats.keyspaceHits()));
        keyspaceMisses.set(Math.max(0L, stats.keyspaceMisses()));
        evictsOnlyVolatileKeys.set(stats.evictsOnlyVolatileKeys() ? 1 : 0);
    }

    /**
     * An unavailable sample keeps the last-known counts. Reporting 0 for a scan that never ran
     * would be indistinguishable from the leak having been fixed.
     */
    public void updateKeyspace(KeyspaceSample sample) {
        Objects.requireNonNull(sample, "sample");
        keyspaceSampleAvailable.set(sample.available() ? 1 : 0);
        if (!sample.available()) return; // keep the last-known counts; only availability drops
        keyspaceSampled.set(Math.max(0, sample.scanned()));
        unexpectedPersistentKeys.set(Math.max(0, sample.unexpected()));
    }
}
