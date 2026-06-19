package com.recsys.infrastructure.redis;

import com.recsys.config.RedisProperties;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
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
     * Latency-sensitive variant of {@link #fromEnv()} that caps the Jedis connect/socket
     * timeout to {@code maxTimeoutMs}. Intended for model-serving recall pools where a
     * down or slow Redis must fail fast so recall falls back to the in-memory path instead
     * of stalling recall-executor threads beyond the per-channel recall budget.
     *
     * @param maxTimeoutMs upper bound on the connect/socket timeout; the effective timeout
     *                     is {@code min(REDIS_TIMEOUT_MS env var, maxTimeoutMs)}
     */
    public static Pool<Jedis> fromEnv(int maxTimeoutMs) {
        Map<String, String> env = System.getenv();
        return create(env, defaultPoolConfig(env), maxTimeoutMs);
    }

    /** Creates a pool from Spring-managed {@link RedisProperties}. */
    public static Pool<Jedis> from(RedisProperties props) {
        GenericObjectPoolConfig<Jedis> poolCfg = poolConfig(props.getPool());
        JedisClientConfig clientCfg = clientConfig(props.getTimeoutMs(), props.getPassword());
        if (props.isSentinelMode()) {
            JedisClientConfig sentinelCfg = DefaultJedisClientConfig.builder().build();
            return new JedisSentinelPool(props.getSentinelMaster(),
                    parseSentinelHostAndPorts(props.getSentinelNodes()), poolCfg, clientCfg, sentinelCfg);
        }
        return new JedisPool(poolCfg, new HostAndPort(props.getHost(), props.getPort()), clientCfg);
    }

    /**
     * Builds an AZ-aware {@link RedisReadReplicaRouter} from Spring-managed {@link RedisProperties}.
     */
    public static RedisReadReplicaRouter routerFrom(RedisProperties props) {
        GenericObjectPoolConfig<Jedis> poolCfg = poolConfig(props.getPool());
        JedisClientConfig clientCfg = clientConfig(props.getTimeoutMs(), props.getPassword());
        Pool<Jedis> primary = from(props);
        String localAz = System.getenv().getOrDefault("AWS_AZ",
                System.getenv().getOrDefault("AVAILABILITY_ZONE", "unknown"));

        List<RedisReadReplicaRouter.AzPool> replicas = new ArrayList<>();
        String replicasSpec = props.getReplicaNodes();
        if (!replicasSpec.isBlank()) {
            for (String spec : replicasSpec.split(",")) {
                spec = spec.strip();
                if (spec.isEmpty()) continue;
                ReplicaConfig cfg = ReplicaConfig.parse(spec);
                replicas.add(new RedisReadReplicaRouter.AzPool(
                        new JedisPool(poolCfg, new HostAndPort(cfg.host(), cfg.port()), clientCfg), cfg.az()));
            }
        }
        return new RedisReadReplicaRouter(primary, replicas, localAz);
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

    static RedisReadReplicaRouter routerFromEnv(Map<String, String> env, GenericObjectPoolConfig<Jedis> poolCfg) {
        Pool<Jedis> primary = create(env, poolCfg);
        String localAz = env.getOrDefault("AWS_AZ",
                env.getOrDefault("AVAILABILITY_ZONE", "unknown"));
        String replicasSpec = env.getOrDefault("REDIS_REPLICA_NODES", "");
        int timeoutMs = readPositiveInt(env, "REDIS_TIMEOUT_MS", Protocol.DEFAULT_TIMEOUT);
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        JedisClientConfig clientCfg = clientConfig(timeoutMs, password);

        List<RedisReadReplicaRouter.AzPool> replicas = new ArrayList<>();
        if (!replicasSpec.isBlank()) {
            for (String spec : replicasSpec.split(",")) {
                spec = spec.strip();
                if (spec.isEmpty()) continue;
                ReplicaConfig cfg = ReplicaConfig.parse(spec);
                replicas.add(new RedisReadReplicaRouter.AzPool(
                        new JedisPool(poolCfg, new HostAndPort(cfg.host(), cfg.port()), clientCfg), cfg.az()));
            }
        }
        return new RedisReadReplicaRouter(primary, replicas, localAz);
    }

    static Pool<Jedis> create(Map<String, String> env, GenericObjectPoolConfig<Jedis> poolCfg) {
        return create(env, poolCfg, Integer.MAX_VALUE);
    }

    /**
     * Computes the effective connect/socket timeout for a Jedis pool.
     * Returns {@code min(REDIS_TIMEOUT_MS env var, maxTimeoutMs)}, floored to 1.
     * Extracted as a package-private helper so unit tests can verify the cap logic
     * without touching the network.
     */
    static int effectiveTimeoutMs(Map<String, String> env, int maxTimeoutMs) {
        int envTimeout = readPositiveInt(env, "REDIS_TIMEOUT_MS", Protocol.DEFAULT_TIMEOUT);
        return Math.min(envTimeout, Math.max(1, maxTimeoutMs));
    }

    static Pool<Jedis> create(Map<String, String> env, GenericObjectPoolConfig<Jedis> poolCfg, int maxTimeoutMs) {
        String mode     = env.getOrDefault("REDIS_MODE", "standalone");
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        int timeoutMs   = effectiveTimeoutMs(env, maxTimeoutMs);
        JedisClientConfig clientCfg = clientConfig(timeoutMs, password);
        if ("sentinel".equalsIgnoreCase(mode)) {
            String master = env.getOrDefault("REDIS_SENTINEL_MASTER", "mymaster");
            String nodes  = env.getOrDefault("REDIS_SENTINEL_NODES", "");
            JedisClientConfig sentinelCfg = DefaultJedisClientConfig.builder().build();
            return new JedisSentinelPool(master, parseSentinelHostAndPorts(nodes), poolCfg, clientCfg, sentinelCfg);
        }
        String host = env.getOrDefault("REDIS_HOST", "localhost");
        int    port = parsePort(env.getOrDefault("REDIS_PORT", "6379"));
        return new JedisPool(poolCfg, new HostAndPort(host, port), clientCfg);
    }

    /** Parses {@code "host:port,..."} into a set of strings (used in tests). */
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

    /** Parses {@code "host:port,..."} into typed {@link HostAndPort} objects for modern sentinel constructors. */
    static Set<HostAndPort> parseSentinelHostAndPorts(String nodes) {
        Set<HostAndPort> result = new LinkedHashSet<>();
        if (nodes == null || nodes.isBlank()) {
            result.add(new HostAndPort("localhost", 26379));
            return result;
        }
        for (String node : nodes.split(",")) {
            node = node.trim();
            if (node.isEmpty()) continue;
            int colon = node.lastIndexOf(':');
            if (colon > 0) {
                result.add(new HostAndPort(node.substring(0, colon), parsePort(node.substring(colon + 1))));
            } else {
                result.add(new HostAndPort(node, 26379));
            }
        }
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

    private static JedisClientConfig clientConfig(int timeoutMs, String password) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(timeoutMs)
                .socketTimeoutMillis(timeoutMs);
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        return builder.build();
    }

    static GenericObjectPoolConfig<Jedis> poolConfig(RedisProperties.Pool pool) {
        GenericObjectPoolConfig<Jedis> cfg = new GenericObjectPoolConfig<>();
        cfg.setMaxTotal(pool.getMaxTotal());
        cfg.setMaxIdle(pool.getMaxIdle());
        cfg.setMinIdle(pool.getMinIdle());
        cfg.setTestOnBorrow(pool.isTestOnBorrow());
        cfg.setBlockWhenExhausted(true);
        cfg.setMaxWait(java.time.Duration.ofMillis(pool.getMaxWaitMs()));
        return cfg;
    }

    static GenericObjectPoolConfig<Jedis> defaultPoolConfig(Map<String, String> env) {
        GenericObjectPoolConfig<Jedis> cfg = new GenericObjectPoolConfig<>();
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
