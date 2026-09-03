package com.recsys.infrastructure.redis;

import com.recsys.metrics.ConsistencyMetrics;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

/** Bounded sampler of the streaming sinks' authoritative {@code *:updated_at} metadata. */
public final class RedisFeatureVersionSampler implements AutoCloseable {
    private final RedisExecutor redis; private final ConsistencyMetrics metrics;
    private final Clock clock; private final int limit; private ScheduledExecutorService executor;
    /** Guards the scheduled sample so an Error cannot cancel the schedule and freeze the gauges. */
    private final com.recsys.resilience.GuardedLoop loop =
            new com.recsys.resilience.GuardedLoop("redis-feature-version-sampler", this::sample);
    public RedisFeatureVersionSampler(RedisExecutor redis, ConsistencyMetrics metrics, Clock clock, int limit) {
        this.redis = Objects.requireNonNull(redis); this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock); if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
    }
    /**
     * Returns whether a version was actually observed. Either way the availability gauge is left
     * describing this attempt: a scan that matched nothing, held nothing parseable, or could not
     * run at all leaves {@code redis_feature_version_age_seconds} frozen at its last good value,
     * and that stale reading must not be mistaken for a fresh one. Failures are rethrown — the
     * scheduler in {@link #start} is what decides to swallow them, not this method.
     */
    public boolean sample() {
        boolean sampled;
        try {
            sampled = redis.executePrimaryRead(commands -> {
                KeyScanCursor<String> cursor = commands.scan(ScanArgs.Builder.matches("*:updated_at").limit(limit));
                long min = Long.MAX_VALUE, max = Long.MIN_VALUE; int scanned = 0;
                for (String key : cursor.getKeys()) {
                    if (scanned++ >= limit) break;
                    String raw = commands.get(key); if (raw == null) continue;
                    try { long version = Long.parseLong(raw); min = Math.min(min, version); max = Math.max(max, version); }
                    catch (NumberFormatException ignored) { }
                }
                if (min == Long.MAX_VALUE) return false;
                metrics.updateFeatureVersions(min, max, Duration.ofMillis(Math.max(0, clock.millis() - max)));
                return true;
            });
        } catch (RuntimeException | Error e) {
            // Error too: an OutOfMemoryError lands on whichever thread fails the allocation, and
            // this one allocates a scan cursor. Availability must flip to 0 for it as well.
            metrics.markFeatureVersionSampleUnavailable();
            throw e;
        }
        if (!sampled) metrics.markFeatureVersionSampleUnavailable();
        return sampled;
    }
    public synchronized void start(Duration interval) {
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        if (executor != null) throw new IllegalStateException("sampler already started");
        executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "redis-feature-version-sampler"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(loop, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }
    /** The loop's health (age since last good sample, failure count); bind it to a registry to publish. */
    public com.recsys.resilience.GuardedLoop loop() { return loop; }
    @Override public synchronized void close() {
        if (executor == null) return; executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        executor = null;
    }
}
