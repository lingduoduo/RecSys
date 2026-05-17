package com.recsys.features;

import com.recsys.streaming.TrendingStore;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class RedisTopKStore implements TrendingStore {
    private static final long DEFAULT_CACHE_TTL_MS = 2_000L;

    private final JedisPool pool;
    private final String keyPrefix;
    private final long cacheTtlMs;
    private final ConcurrentHashMap<CacheKey, CachedIds> hotTopKCache = new ConcurrentHashMap<>();

    public RedisTopKStore(JedisPool pool, String keyPrefix) {
        this(pool, keyPrefix, DEFAULT_CACHE_TTL_MS);
    }

    public RedisTopKStore(JedisPool pool, String keyPrefix, long cacheTtlMs) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
        this.cacheTtlMs = Math.max(0L, cacheTtlMs);
    }

    public List<String> getTopKIds(String window, int k) {
        CacheKey cacheKey = new CacheKey(window, k);
        long now = System.currentTimeMillis();
        CachedIds cached = hotTopKCache.get(cacheKey);
        if (cached != null && cached.expiresAtMs > now) {
            return cached.ids;
        }

        String key = keyPrefix + window;
        try (Jedis jedis = pool.getResource()) {
            List<String> ids = List.copyOf(jedis.zrevrange(key, 0, k - 1));
            if (cacheTtlMs > 0L) {
                hotTopKCache.put(cacheKey, new CachedIds(ids, now + cacheTtlMs));
            }
            return ids;
        }
    }

    public int hotCacheSize() {
        return hotTopKCache.size();
    }

    private record CacheKey(String window, int k) {}

    private record CachedIds(List<String> ids, long expiresAtMs) {}
}
