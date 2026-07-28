package com.recsys.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.recsys.infrastructure.resilience.SingleFlight;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 *
 * All three internal maps are bounded Caffeine caches, so a sweep over many distinct or
 * absent IDs cannot grow the heap without limit. {@code maximumSize} is configurable via
 * {@code LOGICAL_EXPIRY_CACHE_MAX_ENTRIES} (default 10 000). Note the value cache is bounded
 * by <em>size only</em>: a time-based eviction there would break serve-stale, since an entry
 * past its soft expiry must remain servable until a refresh replaces it.
 */
public final class LogicalExpiryEmbeddingCache implements EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(LogicalExpiryEmbeddingCache.class);

    private record LogicalEntry(float[] value, long softExpiresAtMs) {}

    public static final long DEFAULT_NULL_SENTINEL_TTL_MS = 30_000L;

    static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final EmbeddingStore backingStore;
    private final long softTtlMs;
    private final long nullSentinelTtlMs;

    // Bounded by size ONLY — deliberately no expireAfterWrite. The softExpiresAtMs stamped
    // into each LogicalEntry is the *fresh-vs-stale* signal; an entry past it is still served,
    // and triggers one background refresh. Adding a time-based eviction here would defeat that:
    // if the backing store were down, entries would evaporate and every reader would cold-miss,
    // which is exactly the thundering herd this class exists to prevent. Size is the only
    // dimension that needed bounding.
    private final Cache<Integer, LogicalEntry> cache;
    // Negative cache of recently-confirmed-absent IDs: skips the backing store so brand-new
    // users do not re-hit Redis each request. Membership alone means "recently absent" —
    // expireAfterWrite supplies the TTL the stored timestamp used to carry.
    private final Cache<Integer, Boolean> nullSentinels;
    // Tracks IDs with in-flight background refreshes to avoid duplicate tasks.
    private final Cache<Integer, Boolean> refreshing;

    private final SingleFlight<Integer, float[]> coldMissSingleFlight = new SingleFlight<>(2_000L);
    private final Executor refreshExecutor;

    public LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlSeconds) {
        this(backingStore, softTtlSeconds * 1_000L, DEFAULT_NULL_SENTINEL_TTL_MS,
                ForkJoinPool.commonPool(),
                readIntEnv("LOGICAL_EXPIRY_CACHE_MAX_ENTRIES", DEFAULT_MAX_ENTRIES));
    }

    // softTtlMs is in milliseconds — allows sub-second TTLs for testing.
    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs, Executor refreshExecutor) {
        this(backingStore, softTtlMs, DEFAULT_NULL_SENTINEL_TTL_MS, refreshExecutor, DEFAULT_MAX_ENTRIES);
    }

    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs,
                                long nullSentinelTtlMs, Executor refreshExecutor) {
        this(backingStore, softTtlMs, nullSentinelTtlMs, refreshExecutor, DEFAULT_MAX_ENTRIES);
    }

    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs, long nullSentinelTtlMs,
                                Executor refreshExecutor, int maxEntries) {
        this.backingStore = backingStore;
        this.softTtlMs = Math.max(1L, softTtlMs);
        this.nullSentinelTtlMs = Math.max(1L, nullSentinelTtlMs);
        this.refreshExecutor = refreshExecutor;
        int cap = Math.max(1, maxEntries);
        this.cache = Caffeine.newBuilder()
                .maximumSize(cap)
                .executor(Runnable::run) // synchronous maintenance -> deterministic size in tests
                .build();
        this.nullSentinels = Caffeine.newBuilder()
                .maximumSize(cap)
                .expireAfterWrite(Duration.ofMillis(this.nullSentinelTtlMs))
                .executor(Runnable::run)
                .build();
        // Bounded by the same cap — it can never hold more distinct IDs than the cache it
        // refreshes. The hard TTL doubles as a backstop: a refresh task that dies without
        // reaching its finally block cannot wedge an ID out of refresh forever.
        this.refreshing = Caffeine.newBuilder()
                .maximumSize(cap)
                .expireAfterWrite(Duration.ofMinutes(1))
                .executor(Runnable::run)
                .build();
    }

    private static int readIntEnv(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public float[] getEmbedding(int id) {
        long now = System.currentTimeMillis();
        LogicalEntry entry = cache.getIfPresent(id);

        if (entry != null) {
            if (entry.softExpiresAtMs() <= now) {
                // Past soft expiry: return stale value and schedule one background refresh.
                scheduleRefresh(id);
            }
            return entry.value();
        }

        // No positive entry — check the negative cache before hitting the backing store.
        // Presence is sufficient: Caffeine has already dropped it if the sentinel TTL passed.
        if (nullSentinels.getIfPresent(id) != null) {
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
            LogicalEntry entry = cache.getIfPresent(id);
            if (entry != null) {
                if (entry.softExpiresAtMs() <= now) scheduleRefresh(id);
                result.put(id, entry.value());
            } else {
                if (nullSentinels.getIfPresent(id) != null) continue; // known absent
                coldMisses.add(id);
            }
        }

        // Batch-resolve all cold misses in a single backing-store call (one Redis MGET instead of N
        // individual round-trips). Concurrent single-key getEmbedding() calls are still deduped via
        // coldMissSingleFlight; concurrent batch callers may race on writes, but those are idempotent.
        if (!coldMisses.isEmpty()) {
            Map<Integer, float[]> batchResult = backingStore.getEmbeddings(coldMisses);
            long softExpiry = System.currentTimeMillis() + softTtlMs;
            for (int id : coldMisses) {
                float[] value = batchResult.get(id);
                if (value != null) {
                    cache.put(id, new LogicalEntry(value, softExpiry));
                    result.put(id, value);
                } else {
                    nullSentinels.put(id, Boolean.TRUE);
                }
            }
        }

        return result;
    }

    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        backingStore.setEmbedding(id, vector, ttlSeconds);
        nullSentinels.invalidate(id);
        cache.put(id, new LogicalEntry(vector, System.currentTimeMillis() + softTtlMs));
    }

    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        backingStore.setEmbeddings(vectors, ttlSeconds);
        long softExpiry = System.currentTimeMillis() + softTtlMs;
        vectors.forEach((id, vec) -> {
            nullSentinels.invalidate(id);
            cache.put(id, new LogicalEntry(vec, softExpiry));
        });
    }

    @Override
    public Set<Integer> scanIds(int maxKeys) {
        return backingStore.scanIds(maxKeys);
    }

    private void scheduleRefresh(int id) {
        // putIfAbsent is the singleflight guard: only one refresh task per ID.
        if (refreshing.asMap().putIfAbsent(id, Boolean.TRUE) != null) return;
        refreshExecutor.execute(() -> {
            try {
                LogicalEntry stale = cache.getIfPresent(id);   // capture before refresh
                float[] fresh = backingStore.getEmbedding(id);
                if (fresh != null) {
                    cache.put(id, new LogicalEntry(fresh, System.currentTimeMillis() + softTtlMs));
                } else if (stale != null && cache.asMap().remove(id, stale)) {
                    // Only evict when no concurrent write replaced the stale entry.
                    nullSentinels.put(id, Boolean.TRUE);
                }
            } catch (Exception e) {
                log.warn("Background refresh failed for embedding {}: {}", id, e.toString());
            } finally {
                refreshing.invalidate(id);
            }
        });
    }

    private float[] loadColdMiss(int id) {
        LogicalEntry cached = cache.getIfPresent(id);
        if (cached != null) return cached.value();

        float[] value = backingStore.getEmbedding(id); // throws → propagates, no sentinel recorded
        if (value != null) {
            cache.put(id, new LogicalEntry(value, System.currentTimeMillis() + softTtlMs));
        } else {
            nullSentinels.put(id, Boolean.TRUE);
        }
        return value;
    }

    int cacheSize() { return (int) cache.estimatedSize(); }
    int nullSentinelSize() { return (int) nullSentinels.estimatedSize(); }
    boolean isRefreshing(int id) { return refreshing.getIfPresent(id) != null; }
    int inflightColdMisses() { return coldMissSingleFlight.inflightCount(); }
    boolean hasNullSentinel(int id) { return nullSentinels.getIfPresent(id) != null; }
}
