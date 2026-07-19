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
    public RedisFeatureVersionSampler(RedisExecutor redis, ConsistencyMetrics metrics, Clock clock, int limit) {
        this.redis = Objects.requireNonNull(redis); this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock); if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
    }
    public boolean sample() {
        return redis.executePrimaryRead(commands -> {
            KeyScanCursor<String> cursor = commands.scan(ScanArgs.Builder.matches("*:updated_at").limit(limit));
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE; int sampled = 0;
            for (String key : cursor.getKeys()) {
                if (sampled++ >= limit) break;
                String raw = commands.get(key); if (raw == null) continue;
                try { long version = Long.parseLong(raw); min = Math.min(min, version); max = Math.max(max, version); }
                catch (NumberFormatException ignored) { }
            }
            if (min == Long.MAX_VALUE) return false;
            metrics.updateFeatureVersions(min, max, Duration.ofMillis(Math.max(0, clock.millis() - max)));
            return true;
        });
    }
    public synchronized void start(Duration interval) {
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        if (executor != null) throw new IllegalStateException("sampler already started");
        executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "redis-feature-version-sampler"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(() -> { try { sample(); } catch (RuntimeException ignored) { } }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }
    @Override public synchronized void close() {
        if (executor == null) return; executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        executor = null;
    }
}
