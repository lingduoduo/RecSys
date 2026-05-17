package com.recsys.features;

import com.recsys.streaming.TrendingStore;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int MAX_FULL_CACHE_SIZE = 100;

    private final JedisPool pool;
    private final String keyPrefix;
    private final long cacheTtlMs;
    private final ConcurrentHashMap<String, CachedIds> hotTopKCache = new ConcurrentHashMap<>();

    public RedisTopKStore(JedisPool pool, String keyPrefix) {
        this(pool, keyPrefix, DEFAULT_CACHE_TTL_MS);
    }

    public RedisTopKStore(JedisPool pool, String keyPrefix, long cacheTtlMs) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
        this.cacheTtlMs = Math.max(0L, cacheTtlMs);
    }

    public List<String> getTopKIds(String window, int k) {
        long now = System.currentTimeMillis();
        CachedIds cached = hotTopKCache.get(window);
        if (cached != null && cached.expiresAtMs > now) {
            return slice(cached.ids, k);
        }

        // Always fetch a full list so different k values share the same cache entry.
        int fetchSize = Math.max(k, MAX_FULL_CACHE_SIZE);
        String key = keyPrefix + window;
        try (Jedis jedis = pool.getResource()) {
            List<String> ids = List.copyOf(jedis.zrevrange(key, 0, fetchSize - 1));
            if (cacheTtlMs > 0L) {
                hotTopKCache.put(window, new CachedIds(ids, now + cacheTtlMs));
            }
            return slice(ids, k);
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
