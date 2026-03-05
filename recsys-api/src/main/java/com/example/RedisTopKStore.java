package com.example;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

public final class RedisTopKStore {
    private final JedisPool pool;
    private final String keyPrefix;

    public RedisTopKStore(JedisPool pool, String keyPrefix) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
    }

    public List<String> getTopKIds(String window, int k) {
        String key = keyPrefix + window;
        try (Jedis jedis = pool.getResource()) {
            return jedis.zrevrange(key, 0, k - 1);
        }
    }
}
