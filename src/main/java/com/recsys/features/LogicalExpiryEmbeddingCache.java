package com.recsys.features;

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
 * Logical-expiry wrapper for EmbeddingStore to prevent cache breakdown (缓存击穿) on hot keys.
 *
 * Problem: when a hot Redis key's TTL expires all readers rush to the backing store
 * simultaneously, causing a thundering herd that overwhelms the data source.
 *
 * Solution: embed a "soft" (logical) expiry inside each cache entry and give the backing
 * store key a much longer "hard" TTL (e.g., 2× softTtlSeconds).  On a read:
 *   - Before soft expiry  → cache hit, return immediately.
 *   - Past soft expiry    → return the stale (but valid) value AND schedule exactly one
 *                           background refresh via {@link #scheduleRefresh}; no herd.
 *   - Cold miss           → synchronous fetch from the backing store and cache the result.
 *
 * Background refreshes are deduped per ID: if a refresh for ID X is already in flight,
 * subsequent soft-expired reads for X just return the stale value without queuing another task.
 *
 * The backing store must be written with a hard TTL longer than softTtlSeconds; use
 * {@code setEmbedding(id, vec, softTtlSeconds * 2)} or {@code -1} for no expiry.
 */
public final class LogicalExpiryEmbeddingCache implements EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(LogicalExpiryEmbeddingCache.class);

    private record LogicalEntry(float[] value, long softExpiresAtMs) {}

    private final EmbeddingStore backingStore;
    private final long softTtlMs;
    private final ConcurrentHashMap<Integer, LogicalEntry> cache = new ConcurrentHashMap<>();
    // Tracks IDs with in-flight background refreshes to avoid duplicate tasks.
    private final ConcurrentHashMap<Integer, Boolean> refreshing = new ConcurrentHashMap<>();
    private final Executor refreshExecutor;

    public LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlSeconds) {
        this(backingStore, softTtlSeconds * 1_000L, ForkJoinPool.commonPool());
    }

    // softTtlMs is in milliseconds — allows sub-second TTLs for testing.
    LogicalExpiryEmbeddingCache(EmbeddingStore backingStore, long softTtlMs, Executor refreshExecutor) {
        this.backingStore = backingStore;
        this.softTtlMs = Math.max(1L, softTtlMs);
        this.refreshExecutor = refreshExecutor;
    }

    @Override
    public float[] getEmbedding(int id) {
        long now = System.currentTimeMillis();
        LogicalEntry entry = cache.get(id);

        if (entry == null) {
            // Cold miss: synchronous fetch, cache with fresh soft expiry.
            float[] value = backingStore.getEmbedding(id);
            if (value != null) {
                cache.put(id, new LogicalEntry(value, now + softTtlMs));
            }
            return value;
        }

        if (entry.softExpiresAtMs() <= now) {
            // Past soft expiry: return stale value and schedule one background refresh.
            scheduleRefresh(id, now);
        }
        return entry.value();
    }

    @Override
    public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
        long now = System.currentTimeMillis();
        Map<Integer, float[]> result = new HashMap<>(ids.size() * 2);
        Set<Integer> coldMisses = new HashSet<>();

        for (int id : ids) {
            LogicalEntry entry = cache.get(id);
            if (entry != null) {
                if (entry.softExpiresAtMs() <= now) scheduleRefresh(id, now);
                result.put(id, entry.value());
            } else {
                coldMisses.add(id);
            }
        }

        if (!coldMisses.isEmpty()) {
            Map<Integer, float[]> fetched = backingStore.getEmbeddings(coldMisses);
            fetched.forEach((id, vec) -> cache.put(id, new LogicalEntry(vec, now + softTtlMs)));
            result.putAll(fetched);
        }

        return result;
    }

    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        backingStore.setEmbedding(id, vector, ttlSeconds);
        cache.put(id, new LogicalEntry(vector, System.currentTimeMillis() + softTtlMs));
    }

    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        backingStore.setEmbeddings(vectors, ttlSeconds);
        long softExpiry = System.currentTimeMillis() + softTtlMs;
        vectors.forEach((id, vec) -> cache.put(id, new LogicalEntry(vec, softExpiry)));
    }

    @Override
    public Set<Integer> scanIds(int maxKeys) {
        return backingStore.scanIds(maxKeys);
    }

    private void scheduleRefresh(int id, long now) {
        // putIfAbsent is the singleflight guard: only one refresh task per ID.
        if (refreshing.putIfAbsent(id, Boolean.TRUE) != null) return;
        refreshExecutor.execute(() -> {
            try {
                float[] fresh = backingStore.getEmbedding(id);
                if (fresh != null) {
                    cache.put(id, new LogicalEntry(fresh, now + softTtlMs));
                }
            } catch (Exception e) {
                log.warn("Background refresh failed for embedding {}: {}", id, e.toString());
            } finally {
                refreshing.remove(id);
            }
        });
    }

    int cacheSize() { return cache.size(); }
    boolean isRefreshing(int id) { return refreshing.containsKey(id); }
}
