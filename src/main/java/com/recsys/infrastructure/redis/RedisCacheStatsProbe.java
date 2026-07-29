package com.recsys.infrastructure.redis;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Samples Redis's own cache counters from {@code INFO}, so eviction pressure is visible as
 * itself rather than only indirectly as a rising application-side miss rate.
 *
 * <p>Without this, a cache that is silently evicting and a Redis that is briefly
 * unreachable produce the same application signal. {@code evicted_keys} separates them.
 *
 * <p>Also reports the <em>running</em> {@code maxmemory_policy}: the deployment manifests
 * pin {@code volatile-lru} so that keys without a TTL — {@code shard:topology}, the
 * classpath-seeded embeddings — are never eviction candidates, but a live cluster can drift
 * (a manual {@code CONFIG SET}, an unmanaged instance, a hand-rolled compose file). This is
 * how that drift becomes observable instead of theoretical.
 */
public final class RedisCacheStatsProbe implements AutoCloseable {

    /**
     * One {@code INFO} sample. Counters are cumulative Redis totals, not deltas.
     *
     * @param evictsOnlyVolatileKeys true when the running policy confines eviction to
     *                               TTL-bearing keys (any {@code volatile-*} policy) or
     *                               disables it entirely ({@code noeviction}).
     */
    public record CacheStats(boolean available,
                             long usedMemoryBytes,
                             long maxMemoryBytes,
                             long evictedKeys,
                             long keyspaceHits,
                             long keyspaceMisses,
                             boolean evictsOnlyVolatileKeys) {

        public static CacheStats unavailable() {
            return new CacheStats(false, 0L, 0L, 0L, 0L, 0L, false);
        }
    }

    private final RedisExecutor redis;
    private ScheduledExecutorService executor;

    public RedisCacheStatsProbe(RedisExecutor redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    public CacheStats sample() {
        try {
            String info = redis.execute(commands -> commands.info());
            if (info == null) return CacheStats.unavailable();
            String policy = field(info, "maxmemory_policy");
            return new CacheStats(true,
                    longField(info, "used_memory"),
                    longField(info, "maxmemory"),
                    longField(info, "evicted_keys"),
                    longField(info, "keyspace_hits"),
                    longField(info, "keyspace_misses"),
                    protectsKeysWithoutTtl(policy));
        } catch (RuntimeException failure) {
            return CacheStats.unavailable();
        }
    }

    /** An absent policy reads as unsafe: unknown is not a claim of protection. */
    private static boolean protectsKeysWithoutTtl(String policy) {
        if (policy == null) return false;
        String normalized = policy.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("volatile-") || normalized.equals("noeviction");
    }

    private static String field(String info, String name) {
        for (String line : info.split("\\R")) {
            String candidate = line.strip();
            if (!candidate.startsWith(name + ":")) continue;
            return candidate.substring(name.length() + 1).strip();
        }
        return null;
    }

    /** A missing or unparsable field reads as 0 — Redis's own encoding of "no limit" for maxmemory. */
    private static long longField(String info, String name) {
        String raw = field(info, name);
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    public synchronized void start(Duration interval, Consumer<CacheStats> observer) {
        Objects.requireNonNull(interval);
        Objects.requireNonNull(observer);
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (executor != null) throw new IllegalStateException("probe already started");
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "redis-cache-stats-probe");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                observer.accept(sample());
            } catch (Throwable ignored) {
                // An observer must not permanently cancel fixed-delay sampling.
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }
}
