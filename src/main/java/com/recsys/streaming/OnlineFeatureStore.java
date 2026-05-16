package com.recsys.streaming;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    private final JedisPool pool;
    private final long cacheTtlMs;
    private final ConcurrentHashMap<Integer, CachedHistory> historyCache = new ConcurrentHashMap<>();

    public OnlineFeatureStore(JedisPool pool) {
        this(pool, DEFAULT_CACHE_TTL_MS);
    }

    OnlineFeatureStore(JedisPool pool, long cacheTtlMs) {
        this.pool = pool;
        this.cacheTtlMs = cacheTtlMs;
    }

    @Override
    public List<Integer> getRecentMovieIds(int userId, int limit) {
        long now = System.currentTimeMillis();
        CachedHistory cached = historyCache.get(userId);
        if (cached != null && cached.expiresAtMs > now) {
            return applyLimit(cached.ids, limit);
        }

        List<Integer> ids = fetchFromRedis(userId);
        historyCache.put(userId, new CachedHistory(ids, now + cacheTtlMs));
        return applyLimit(ids, limit);
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

    private record CachedHistory(List<Integer> ids, long expiresAtMs) {}
}
