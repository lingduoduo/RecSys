package com.recsys.streaming;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tier-2/Tier-3 bridge for recent user history.
 *
 * Recent-history keys change on every user interaction (written by Flink) so they
 * must live in Redis (Tier 2). However, recommendation requests read the same key
 * many times per second for active users. A short-TTL JVM-heap cache (Tier 3) absorbs
 * those repeated reads without sacrificing meaningful freshness — a 5-second stale
 * window is imperceptible to recommendation quality.
 */
public final class OnlineFeatureStore implements RecentHistoryStore {

    private static final long DEFAULT_CACHE_TTL_MS = 5_000L;
    private static final int DEFAULT_MAX_CACHE_USERS = 10_000;
    private static final long EVICT_INTERVAL_MS = 5_000L;
    private static final long REDIS_FETCH_TIMEOUT_MS = 2_000L;

    private final JedisPool pool;
    private final long cacheTtlMs;
    private final int maxCacheUsers;
    private final ConcurrentHashMap<Integer, CachedHistory> historyCache = new ConcurrentHashMap<>();
    // Deduplicates concurrent cache misses for the same userId so only one thread fetches Redis.
    private final ConcurrentHashMap<Integer, CompletableFuture<CachedHistory>> inflight = new ConcurrentHashMap<>();
    private volatile long lastEvictMs = 0L;

    public OnlineFeatureStore(JedisPool pool) {
        this(pool, DEFAULT_CACHE_TTL_MS, readIntEnv("ONLINE_FEATURE_CACHE_MAX_USERS", DEFAULT_MAX_CACHE_USERS));
    }

    OnlineFeatureStore(JedisPool pool, long cacheTtlMs) {
        this(pool, cacheTtlMs, DEFAULT_MAX_CACHE_USERS);
    }

    OnlineFeatureStore(JedisPool pool, long cacheTtlMs, int maxCacheUsers) {
        this.pool = pool;
        this.cacheTtlMs = cacheTtlMs;
        this.maxCacheUsers = Math.max(1, maxCacheUsers);
    }

    @Override
    public List<Integer> getRecentMovieIds(int userId, int limit) {
        long now = System.currentTimeMillis();
        // Fast path: no lock needed.
        CachedHistory cached = historyCache.get(userId);
        if (cached != null && cached.expiresAtMs > now) {
            return applyLimit(cached.ids, limit);
        }

        // Slow path: use an in-flight future to deduplicate concurrent misses for the
        // same userId WITHOUT holding a CHM bin lock across the Redis round-trip.
        // (compute() would hold the bin lock during fetchFromRedis(), blocking ALL
        // threads whose userId hashes to the same segment — the root cause of thread stalls.)
        CompletableFuture<CachedHistory> myFuture = new CompletableFuture<>();
        CompletableFuture<CachedHistory> existing = inflight.putIfAbsent(userId, myFuture);

        if (existing == null) {
            // This thread owns the fetch.
            try {
                evictIfNeeded(now);
                CachedHistory fresh = new CachedHistory(fetchFromRedis(userId), now + cacheTtlMs);
                historyCache.put(userId, fresh);
                myFuture.complete(fresh);
                return applyLimit(fresh.ids, limit);
            } catch (RuntimeException ex) {
                myFuture.completeExceptionally(ex);
                throw ex;
            } finally {
                inflight.remove(userId, myFuture);
            }
        }

        // Another thread is already fetching; wait briefly for its result.
        try {
            return applyLimit(existing.get(REDIS_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS).ids, limit);
        } catch (TimeoutException | InterruptedException | ExecutionException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            // Fail open: fetch independently rather than returning stale/empty data.
            CachedHistory fresh = new CachedHistory(fetchFromRedis(userId), now + cacheTtlMs);
            historyCache.put(userId, fresh);
            return applyLimit(fresh.ids, limit);
        }
    }

    private List<Integer> fetchFromRedis(int userId) {
        String key = "user:" + userId + ":recent_movies";
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(key);
            if (value == null || value.isBlank()) return List.of();

            String[] tokens = value.trim().split("\\s+");
            List<Integer> ids = new ArrayList<>(tokens.length);
            for (String token : tokens) {
                try {
                    ids.add(Integer.parseInt(token));
                } catch (NumberFormatException ignore) {
                    // Ignore malformed feature values so one bad token does not break the demo.
                }
            }
            return List.copyOf(ids);
        }
    }

    private static List<Integer> applyLimit(List<Integer> ids, int limit) {
        if (ids.size() <= limit) return ids;
        return ids.subList(ids.size() - limit, ids.size());
    }

    private void evictIfNeeded(long now) {
        if (historyCache.size() < maxCacheUsers) return;
        // Rate-limit eviction scans: a full O(N) removeIf on every miss is too expensive.
        if (now - lastEvictMs < EVICT_INTERVAL_MS) return;
        lastEvictMs = now;
        // First pass: remove genuinely expired entries.
        historyCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs <= now);
        // Second pass: if still over capacity, evict arbitrary live entries via an
        // iterator — safe under concurrent modification, avoids Enumeration.nextElement()
        // which can throw NoSuchElementException when the map is modified concurrently.
        if (historyCache.size() >= maxCacheUsers) {
            Iterator<Integer> it = historyCache.keySet().iterator();
            while (historyCache.size() >= maxCacheUsers && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    public int cacheSize() {
        return historyCache.size();
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

    private record CachedHistory(List<Integer> ids, long expiresAtMs) {}
}
