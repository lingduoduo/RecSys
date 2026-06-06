package com.recsys.infrastructure.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.util.Pool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RedisConnectionFactory {

    static final int DEFAULT_MAX_TOTAL = 50;
    static final int DEFAULT_MAX_IDLE  = 10;
    static final int DEFAULT_MIN_IDLE  = 2;
    static final int DEFAULT_MAX_WAIT_MS = 250;

    private RedisConnectionFactory() {}

    public static Pool<Jedis> fromEnv() {
        Map<String, String> env = System.getenv();
        return create(env, defaultPoolConfig(env));
    }

    /**
     * Builds an AZ-aware {@link RedisReadReplicaRouter} from environment variables.
     *
     * <ul>
     *   <li>{@code REDIS_REPLICA_NODES} — comma-separated replica specs in
     *       {@code host:port@az} format (e.g.
     *       {@code redis-b:6379@us-east-1b,redis-c:6379@us-east-1c}).
     *       When absent, the router's read and write pools both point to the
     *       primary, preserving backwards-compatible behaviour.</li>
     *   <li>{@code AWS_AZ} (or {@code AVAILABILITY_ZONE}) — AZ of this service
     *       instance, injected by ECS/EKS task metadata.</li>
     * </ul>
     */
    public static RedisReadReplicaRouter routerFromEnv() {
        Map<String, String> env = System.getenv();
        return routerFromEnv(env, defaultPoolConfig(env));
    }

    static RedisReadReplicaRouter routerFromEnv(Map<String, String> env, JedisPoolConfig config) {
        Pool<Jedis> primary = create(env, config);
        String localAz = env.getOrDefault("AWS_AZ",
                env.getOrDefault("AVAILABILITY_ZONE", "unknown"));
        String replicasSpec = env.getOrDefault("REDIS_REPLICA_NODES", "");

        List<RedisReadReplicaRouter.AzPool> replicas = new ArrayList<>();
        if (!replicasSpec.isBlank()) {
            for (String spec : replicasSpec.split(",")) {
                spec = spec.strip();
                if (spec.isEmpty()) continue;
                ReplicaConfig cfg = ReplicaConfig.parse(spec);
                JedisPool pool = new JedisPool(config, cfg.host(), cfg.port());
                replicas.add(new RedisReadReplicaRouter.AzPool(pool, cfg.az()));
            }
        }
        return new RedisReadReplicaRouter(primary, replicas, localAz);
    }

    static Pool<Jedis> create(Map<String, String> env, JedisPoolConfig config) {
        String mode     = env.getOrDefault("REDIS_MODE", "standalone");
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        int timeoutMs = readPositiveInt(env, "REDIS_TIMEOUT_MS", Protocol.DEFAULT_TIMEOUT);
        if ("sentinel".equalsIgnoreCase(mode)) {
            String master = env.getOrDefault("REDIS_SENTINEL_MASTER", "mymaster");
            String nodes  = env.getOrDefault("REDIS_SENTINEL_NODES", "");
            return password.isEmpty()
                ? new JedisSentinelPool(master, parseSentinelNodes(nodes), config, timeoutMs)
                : new JedisSentinelPool(master, parseSentinelNodes(nodes), config, timeoutMs, password);
        }
        String host = env.getOrDefault("REDIS_HOST", "localhost");
        int    port = parsePort(env.getOrDefault("REDIS_PORT", "6379"));
        return password.isEmpty()
            ? new JedisPool(config, host, port, timeoutMs)
            : new JedisPool(config, host, port, timeoutMs, password);
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

    static JedisPoolConfig defaultPoolConfig(Map<String, String> env) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(readPositiveInt(env, "REDIS_POOL_MAX_TOTAL", DEFAULT_MAX_TOTAL));
        cfg.setMaxIdle(readPositiveInt(env, "REDIS_POOL_MAX_IDLE", DEFAULT_MAX_IDLE));
        cfg.setMinIdle(readNonNegativeInt(env, "REDIS_POOL_MIN_IDLE", DEFAULT_MIN_IDLE));
        cfg.setTestOnBorrow(Boolean.parseBoolean(env.getOrDefault("REDIS_POOL_TEST_ON_BORROW", "true")));
        cfg.setBlockWhenExhausted(true);
        cfg.setMaxWait(java.time.Duration.ofMillis(
                readPositiveInt(env, "REDIS_POOL_MAX_WAIT_MS", DEFAULT_MAX_WAIT_MS)));
        return cfg;
    }

    private static int readPositiveInt(Map<String, String> env, String name, int defaultValue) {
        return Math.max(1, readInt(env.get(name), defaultValue));
    }

    private static int readNonNegativeInt(Map<String, String> env, String name, int defaultValue) {
        return Math.max(0, readInt(env.get(name), defaultValue));
    }

    private static int readInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
