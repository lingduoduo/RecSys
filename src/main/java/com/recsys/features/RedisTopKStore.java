package com.recsys.features;

import com.recsys.streaming.TrendingStore;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Redis-backed Top-K trending store with a JVM hot-data cache.
 *
 * The cache is keyed by window name only (not by k), so a request for k=5 and
 * k=20 on the same window share one Redis fetch per TTL.  The full list
 * (up to {@value #MAX_FULL_CACHE_SIZE} items) is cached and sliced on read,
 * eliminating duplicate Redis round-trips for the same trending window.
 */
public final class RedisTopKStore implements TrendingStore {
    private static final long DEFAULT_CACHE_TTL_MS = 2_000L;
    private static final long FETCH_WAIT_TIMEOUT_MS = 2_000L;
    private static final int MAX_FULL_CACHE_SIZE = 100;

    private final JedisPool pool;
    private final String keyPrefix;
    private final long cacheTtlMs;
    private final ConcurrentHashMap<String, CachedIds> hotTopKCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<CachedIds>> inflight = new ConcurrentHashMap<>();

    public RedisTopKStore(JedisPool pool, String keyPrefix) {
        this(pool, keyPrefix, DEFAULT_CACHE_TTL_MS);
    }

    public RedisTopKStore(JedisPool pool, String keyPrefix, long cacheTtlMs) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
        this.cacheTtlMs = Math.max(0L, cacheTtlMs);
    }

    public List<String> getTopKIds(String window, int k) {
        if (k <= 0) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        CachedIds cached = hotTopKCache.get(window);
        if (cached != null && cached.expiresAtMs > now) {
            return slice(cached.ids, k);
        }

        CompletableFuture<CachedIds> myFuture = new CompletableFuture<>();
        CompletableFuture<CachedIds> existing = inflight.putIfAbsent(window, myFuture);

        if (existing == null) {
            try {
                CachedIds fresh = fetchWindow(window, k, now);
                myFuture.complete(fresh);
                return slice(fresh.ids, k);
            } catch (RuntimeException ex) {
                myFuture.completeExceptionally(ex);
                throw ex;
            } finally {
                inflight.remove(window, myFuture);
            }
        }

        try {
            return slice(existing.get(FETCH_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS).ids, k);
        } catch (TimeoutException | InterruptedException | ExecutionException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            CachedIds fresh = fetchWindow(window, k, now);
            return slice(fresh.ids, k);
        }
    }

    private CachedIds fetchWindow(String window, int k, long now) {
        // Always fetch a full list so different k values share the same cache entry.
        int fetchSize = Math.max(k, MAX_FULL_CACHE_SIZE);
        String key = keyPrefix + window;
        try (Jedis jedis = pool.getResource()) {
            CachedIds ids = new CachedIds(List.copyOf(jedis.zrevrange(key, 0, fetchSize - 1)), now + cacheTtlMs);
            if (cacheTtlMs > 0L) {
                hotTopKCache.put(window, ids);
            }
            return ids;
        }
    }

    public int hotCacheSize() {
        return hotTopKCache.size();
    }

    private static List<String> slice(List<String> ids, int k) {
        return ids.size() <= k ? ids : ids.subList(0, k);
    }

    private record CachedIds(List<String> ids, long expiresAtMs) {}
}
