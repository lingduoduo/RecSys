package com.recsys.infrastructure.cache;

import com.recsys.infrastructure.resilience.SingleFlight;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Logical-expiry wrapper for EmbeddingStore to prevent cache breakdown (thundering herd) on hot keys.
 *
 * Problem: when a hot Redis key's TTL expires all readers rush to the backing store
 * simultaneously, causing a thundering herd that overwhelms the data source.
 *
 * Solution: embed a "soft" (logical) expiry inside each cache entry and give the backing
 * store key a much longer "hard" TTL (e.g., 2× softTtlSeconds).  On a read:
 *   - Before soft expiry  → cache hit, return immediately.
 *   - Past soft expiry    → return the stale (but valid) value AND schedule exactly one
 *                           background refresh via {@link #scheduleRefresh}; no herd.
 *   - Cold miss           → synchronous singleflight fetch from the backing store and
 *                           cache the result.
 *
 * Cold misses and background refreshes are deduped per ID: if work for ID X is already
 * in flight, concurrent readers share it or return the stale value without queuing
 * another task.
 *
 * The backing store must be written with a hard TTL longer than softTtlSeconds; use
 * {@code setEmbedding(id, vec, softTtlSeconds * 2)} or {@code -1} for no expiry.
 */
public final class LogicalExpiryEmbeddingCache implements EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(LogicalExpiryEmbeddingCache.class);

    private record LogicalEntry(float[] value, long softExpiresAtMs) {}

    public static final long DEFAULT_NULL_SENTINEL_TTL_MS = 30_000L;

    private final EmbeddingStore backingStore;
    private final long softTtlMs;
    private final long nullSentinelTtlMs;
    // Negative cache: absent ID → sentinel expiry timestamp (ms). Skips the backing store
    // for recently-confirmed-absent IDs so brand-new users do not re-hit Redis each request.
    private final ConcurrentHashMap<Integer, Long> nullSentinels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, LogicalEntry> cache = new ConcurrentHashMap<>();
    // Tracks IDs with in-flight background refreshes to avoid duplicate tasks.
    private final ConcurrentHashMap<Integer, Boolean> refreshing = new ConcurrentHashMap<>();
    private final SingleFlight<Integer, float[]> coldMissSingleFlight = new SingleFlight<>(2_000L);
    private final Executor refreshExecutor;

    public LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlSeconds) {
        this(backingStore, softTtlSeconds * 1_000L, DEFAULT_NULL_SENTINEL_TTL_MS, ForkJoinPool.commonPool());
    }

    // softTtlMs is in milliseconds — allows sub-second TTLs for testing.
    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs, Executor refreshExecutor) {
        this(backingStore, softTtlMs, DEFAULT_NULL_SENTINEL_TTL_MS, refreshExecutor);
    }

    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs,
                                long nullSentinelTtlMs, Executor refreshExecutor) {
        this.backingStore = backingStore;
        this.softTtlMs = Math.max(1L, softTtlMs);
        this.nullSentinelTtlMs = Math.max(1L, nullSentinelTtlMs);
        this.refreshExecutor = refreshExecutor;
    }

    @Override
    public float[] getEmbedding(int id) {
        long now = System.currentTimeMillis();
        LogicalEntry entry = cache.get(id);

        if (entry != null) {
            if (entry.softExpiresAtMs() <= now) {
                // Past soft expiry: return stale value and schedule one background refresh.
                scheduleRefresh(id);
            }
            return entry.value();
        }

        // No positive entry — check the negative cache before hitting the backing store.
        Long sentinelExpiry = nullSentinels.get(id);
        if (sentinelExpiry != null && sentinelExpiry > now) {
            return null;
        }

        // Cold miss: synchronous fetch, deduped across concurrent callers.
        return coldMissSingleFlight.execute(id, () -> loadColdMiss(id));
    }

    @Override
    public float[] getEmbeddingPrimary(int id) { return backingStore.getEmbeddingPrimary(id); }

    @Override
    public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();

        long now = System.currentTimeMillis();
        Map<Integer, float[]> result = new HashMap<>(ids.size() * 2);
        Set<Integer> coldMisses = new HashSet<>();

        for (int id : ids) {
            LogicalEntry entry = cache.get(id);
            if (entry != null) {
                if (entry.softExpiresAtMs() <= now) scheduleRefresh(id);
                result.put(id, entry.value());
            } else {
                Long sentinelExpiry = nullSentinels.get(id);
                if (sentinelExpiry != null && sentinelExpiry > now) continue; // known absent
                coldMisses.add(id);
            }
        }

        // Batch-resolve all cold misses in a single backing-store call (one Redis MGET instead of N
        // individual round-trips). Concurrent single-key getEmbedding() calls are still deduped via
        // coldMissSingleFlight; concurrent batch callers may race on writes, but those are idempotent.
        if (!coldMisses.isEmpty()) {
            Map<Integer, float[]> batchResult = backingStore.getEmbeddings(coldMisses);
            long writeNow = System.currentTimeMillis();
            long softExpiry = writeNow + softTtlMs;
            long sentinelExpiry = writeNow + nullSentinelTtlMs;
            for (int id : coldMisses) {
                float[] value = batchResult.get(id);
                if (value != null) {
                    cache.put(id, new LogicalEntry(value, softExpiry));
                    result.put(id, value);
                } else {
                    nullSentinels.put(id, sentinelExpiry);
                }
            }
        }

        return result;
    }

    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        backingStore.setEmbedding(id, vector, ttlSeconds);
        nullSentinels.remove(id);
        cache.put(id, new LogicalEntry(vector, System.currentTimeMillis() + softTtlMs));
    }

    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        backingStore.setEmbeddings(vectors, ttlSeconds);
        long softExpiry = System.currentTimeMillis() + softTtlMs;
        vectors.forEach((id, vec) -> {
            nullSentinels.remove(id);
            cache.put(id, new LogicalEntry(vec, softExpiry));
        });
    }

    @Override
    public Set<Integer> scanIds(int maxKeys) {
        return backingStore.scanIds(maxKeys);
    }

    private void scheduleRefresh(int id) {
        // putIfAbsent is the singleflight guard: only one refresh task per ID.
        if (refreshing.putIfAbsent(id, Boolean.TRUE) != null) return;
        refreshExecutor.execute(() -> {
            try {
                LogicalEntry stale = cache.get(id);            // capture before refresh
                float[] fresh = backingStore.getEmbedding(id);
                if (fresh != null) {
                    cache.put(id, new LogicalEntry(fresh, System.currentTimeMillis() + softTtlMs));
                } else if (stale != null && cache.remove(id, stale)) {
                    // Only evict when no concurrent write replaced the stale entry.
                    nullSentinels.put(id, System.currentTimeMillis() + nullSentinelTtlMs);
                }
            } catch (Exception e) {
                log.warn("Background refresh failed for embedding {}: {}", id, e.toString());
            } finally {
                refreshing.remove(id);
            }
        });
    }

    private float[] loadColdMiss(int id) {
        LogicalEntry cached = cache.get(id);
        if (cached != null) return cached.value();

        float[] value = backingStore.getEmbedding(id); // throws → propagates, no sentinel recorded
        if (value != null) {
            cache.put(id, new LogicalEntry(value, System.currentTimeMillis() + softTtlMs));
        } else {
            nullSentinels.put(id, System.currentTimeMillis() + nullSentinelTtlMs);
        }
        return value;
    }

    int cacheSize() { return cache.size(); }
    boolean isRefreshing(int id) { return refreshing.containsKey(id); }
    int inflightColdMisses() { return coldMissSingleFlight.inflightCount(); }
    boolean hasNullSentinel(int id) {
        Long expiry = nullSentinels.get(id);
        return expiry != null && expiry > System.currentTimeMillis();
    }
}
