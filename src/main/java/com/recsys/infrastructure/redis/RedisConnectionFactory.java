package com.recsys.infrastructure.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.util.Pool;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class RedisConnectionFactory {

    static final int DEFAULT_MAX_TOTAL = 50;
    static final int DEFAULT_MAX_IDLE  = 10;
    static final int DEFAULT_MIN_IDLE  = 2;

    private RedisConnectionFactory() {}

    public static Pool<Jedis> fromEnv() {
        return create(System.getenv(), defaultPoolConfig());
    }

    static Pool<Jedis> create(Map<String, String> env, JedisPoolConfig config) {
        String mode     = env.getOrDefault("REDIS_MODE", "standalone");
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        if ("sentinel".equalsIgnoreCase(mode)) {
            String master = env.getOrDefault("REDIS_SENTINEL_MASTER", "mymaster");
            String nodes  = env.getOrDefault("REDIS_SENTINEL_NODES", "");
            return password.isEmpty()
                ? new JedisSentinelPool(master, parseSentinelNodes(nodes), config)
                : new JedisSentinelPool(master, parseSentinelNodes(nodes), config, password);
        }
        String host = env.getOrDefault("REDIS_HOST", "localhost");
        int    port = parsePort(env.getOrDefault("REDIS_PORT", "6379"));
        return password.isEmpty()
            ? new JedisPool(config, host, port)
            : new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT, password);
    }

    static Set<String> parseSentinelNodes(String nodes) {
        Set<String> result = new LinkedHashSet<>();
        if (nodes == null || nodes.isBlank()) {
            result.add("localhost:26379");
            return result;
        }
        Arrays.stream(nodes.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .forEach(result::add);
        return result;
    }

    static int parsePort(String value) {
        if (value == null) return 6379;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 6379;
        }
    }

    private static JedisPoolConfig defaultPoolConfig() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(DEFAULT_MAX_TOTAL);
        cfg.setMaxIdle(DEFAULT_MAX_IDLE);
        cfg.setMinIdle(DEFAULT_MIN_IDLE);
        cfg.setTestOnBorrow(true);
        cfg.setBlockWhenExhausted(true);
        cfg.setMaxWait(java.time.Duration.ofSeconds(2));
        return cfg;
    }
}
