package com.recsys.infrastructure.redis;

import com.recsys.infrastructure.cache.TtlSingleFlightCache;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Reads the {@code global:item_popularity} sorted set (written by Spark
 * {@code UserEventStreamingJob} via ZINCRBY). The key is user-independent and moves on
 * minute scales, yet it is read on every recall request by both Channels.Popularity and
 * ColdStartChannel. A {@link TtlSingleFlightCache} holds a single top-N snapshot so the
 * Redis ZREVRANGE runs at most once per fresh-TTL window across all concurrent requests.
 */
public class GlobalPopularityStore {

    public static final String KEY = "global:item_popularity";

    // One Redis snapshot holds the top-N list; getTopIds(limit) slices it.
    static final int MAX_CACHED = 100;

    private final RedisExecutor exec;
    private final TtlSingleFlightCache<List<String>> cache;

    public GlobalPopularityStore(RedisExecutor exec) {
        this(exec, TtlSingleFlightCache.DEFAULT_FRESH_TTL_MS,
                TtlSingleFlightCache.DEFAULT_STALE_TTL_MS, System::currentTimeMillis);
    }

    GlobalPopularityStore(RedisExecutor exec, long freshTtlMs, long staleTtlMs, LongSupplier clock) {
        this.exec = exec;
        this.cache = new TtlSingleFlightCache<>(freshTtlMs, staleTtlMs, clock);
    }

    public List<String> getTopIds(int limit) {
        if (limit <= 0) return List.of();
        List<String> top;
        try {
            top = cache.get(KEY, this::loadTopFromRedis);
        } catch (RuntimeException redisDownNoSnapshot) {
            // No usable snapshot and Redis is unavailable — let the caller fall back
            // (Channels.Popularity uses its DataManager fallback on empty).
            return List.of();
        }
        if (top.size() <= limit) return top;
        return List.copyOf(top.subList(0, limit));
    }

    public List<String> getTopIdsPrimary(int limit) {
        if (limit <= 0) return List.of();
        List<String> ids = exec.executePrimaryRead(c -> c.zrevrange(KEY, 0, MAX_CACHED - 1));
        List<String> top = ids == null ? List.of() : List.copyOf(ids);
        return top.size() <= limit ? top : List.copyOf(top.subList(0, limit));
    }

    private List<String> loadTopFromRedis() {
        List<String> ids = exec.executeRead(c -> c.zrevrange(KEY, 0, MAX_CACHED - 1));
        return ids == null ? List.of() : List.copyOf(ids);
    }
}
