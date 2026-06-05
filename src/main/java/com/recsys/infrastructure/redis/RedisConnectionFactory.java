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

    private RedisConnectionFactory() {}

    public static Pool<Jedis> fromEnv() {
        return create(System.getenv(), defaultPoolConfig());
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
        return routerFromEnv(System.getenv(), defaultPoolConfig());
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
