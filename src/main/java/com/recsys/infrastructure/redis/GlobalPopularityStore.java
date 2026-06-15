package com.recsys.infrastructure.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.ArrayList;
import java.util.List;

public class GlobalPopularityStore {

    public static final String KEY = "global:item_popularity";

    private final Pool<Jedis> pool;

    public GlobalPopularityStore(Pool<Jedis> pool) {
        this.pool = pool;
    }

    public List<String> getTopIds(int limit) {
        if (limit <= 0) return List.of();
        try (Jedis jedis = pool.getResource()) {
            return new ArrayList<>(jedis.zrevrange(KEY, 0, limit - 1));
        }
    }
}
