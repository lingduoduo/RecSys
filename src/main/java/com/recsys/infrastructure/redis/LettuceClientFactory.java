package com.recsys.infrastructure.redis;

import com.recsys.config.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds Lettuce-backed {@link RedisExecutor}s and AZ-aware
 * {@link RedisReadReplicaRouter}s from environment variables or Spring-managed
 * {@link RedisProperties}. Drop-in replacement for the previous
 * {@code RedisConnectionFactory}; preserves all env-var names, defaults, and the
 * latency-capped variant used by the model-serving recall path.
 */
public final class LettuceClientFactory {

    static final int DEFAULT_MAX_TOTAL   = 50;
    static final int DEFAULT_MAX_IDLE    = 10;
    static final int DEFAULT_MIN_IDLE    = 2;
    static final int DEFAULT_MAX_WAIT_MS = 250;
    static final int DEFAULT_TIMEOUT_MS  = 2000; // matches Jedis Protocol.DEFAULT_TIMEOUT

    private LettuceClientFactory() {}

    // ── Single executors ──────────────────────────────────────────────────────

    public static RedisExecutor fromEnv() {
        return fromEnv(Integer.MAX_VALUE);
    }

    /**
     * Latency-sensitive variant: caps the command timeout to {@code maxTimeoutMs}
     * so a down/slow Redis fails fast (used by the recall pool).
     */
    public static RedisExecutor fromEnv(int maxTimeoutMs) {
        Map<String, String> env = System.getenv();
        return executor(uriFromEnv(env, maxTimeoutMs), poolConfig(defaultPoolKnobs(env)));
    }

    public static RedisExecutor from(RedisProperties props) {
        return executor(uriFrom(props), poolConfig(props.getPool()));
    }

    // ── Routing executors (execute→primary, executeRead→replica) ──────────────

    /** A read/write-splitting executor: reads use a replica (reader endpoint) when
     *  {@code REDIS_REPLICA_NODES} is set, writes use the primary. */
    public static RedisExecutor routingFromEnv() {
        return new RoutingRedisExecutor(routerFromEnv(System.getenv()));
    }

    /** Latency-capped routing variant (recall pool): caps primary and replica
     *  command timeouts to {@code maxTimeoutMs}. */
    public static RedisExecutor routingFromEnv(int maxTimeoutMs) {
        return new RoutingRedisExecutor(routerFromEnv(System.getenv(), maxTimeoutMs));
    }

    // ── Routers ───────────────────────────────────────────────────────────────

    public static RedisReadReplicaRouter routerFromEnv() {
        Map<String, String> env = System.getenv();
        return routerFromEnv(env);
    }

    static RedisReadReplicaRouter routerFromEnv(Map<String, String> env) {
        return routerFromEnv(env, Integer.MAX_VALUE);
    }

    static RedisReadReplicaRouter routerFromEnv(Map<String, String> env, int maxTimeoutMs) {
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolCfg = poolConfig(defaultPoolKnobs(env));
        int timeoutMs = Math.min(readPositiveInt(env, "REDIS_TIMEOUT_MS", DEFAULT_TIMEOUT_MS), maxTimeoutMs);
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        RedisExecutor primary = executor(uriFromEnv(env, maxTimeoutMs), poolCfg);
        String localAz = env.getOrDefault("AWS_AZ", env.getOrDefault("AVAILABILITY_ZONE", "unknown"));

        List<RedisReadReplicaRouter.AzExecutor> replicas = new ArrayList<>();
        String spec = env.getOrDefault("REDIS_REPLICA_NODES", "");
        if (!spec.isBlank()) {
            for (String node : spec.split(",")) {
                node = node.strip();
                if (node.isEmpty()) continue;
                ReplicaConfig cfg = ReplicaConfig.parse(node);
                RedisURI uri = standaloneUri(cfg.host(), cfg.port(), password, timeoutMs);
                replicas.add(new RedisReadReplicaRouter.AzExecutor(executor(uri, poolCfg), cfg.az()));
            }
        }
        return new RedisReadReplicaRouter(primary, replicas, localAz);
    }

    public static RedisReadReplicaRouter routerFrom(RedisProperties props) {
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolCfg = poolConfig(props.getPool());
        RedisExecutor primary = from(props);
        String localAz = System.getenv().getOrDefault("AWS_AZ",
                System.getenv().getOrDefault("AVAILABILITY_ZONE", "unknown"));

        List<RedisReadReplicaRouter.AzExecutor> replicas = new ArrayList<>();
        String spec = props.getReplicaNodes();
        if (spec != null && !spec.isBlank()) {
            for (String node : spec.split(",")) {
                node = node.strip();
                if (node.isEmpty()) continue;
                ReplicaConfig cfg = ReplicaConfig.parse(node);
                RedisURI uri = standaloneUri(cfg.host(), cfg.port(), props.getPassword(), props.getTimeoutMs());
                replicas.add(new RedisReadReplicaRouter.AzExecutor(executor(uri, poolCfg), cfg.az()));
            }
        }
        return new RedisReadReplicaRouter(primary, replicas, localAz);
    }

    // ── URI construction ────────────────────────────────────────────────────────

    static RedisExecutor executor(RedisURI uri,
                                  GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolCfg) {
        RedisClient client = RedisClient.create(uri);
        client.setOptions(failFastOptions());
        // Connection is opened lazily by the executor (Jedis-pool parity).
        return new LettuceRedisExecutor(client, poolCfg, true);
    }

    /**
     * Bounds command latency the way the previous Jedis socket timeout did. The
     * {@link RedisURI} timeout governs the per-command deadline; {@code TimeoutOptions.enabled()}
     * makes that deadline apply even to commands queued while disconnected, and
     * {@code REJECT_COMMANDS} fails commands immediately when the connection is down
     * instead of buffering them — so a down Redis fails fast (e.g. the recall path's
     * 150 ms cap) instead of stalling callers past their budget.
     */
    static ClientOptions failFastOptions() {
        return ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();
    }

    static RedisURI uriFromEnv(Map<String, String> env, int maxTimeoutMs) {
        String mode = env.getOrDefault("REDIS_MODE", "standalone");
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        int timeoutMs = Math.min(readPositiveInt(env, "REDIS_TIMEOUT_MS", DEFAULT_TIMEOUT_MS),
                Math.max(1, maxTimeoutMs));
        if ("sentinel".equalsIgnoreCase(mode)) {
            String master = env.getOrDefault("REDIS_SENTINEL_MASTER", "mymaster");
            String nodes = env.getOrDefault("REDIS_SENTINEL_NODES", "localhost:26379");
            return sentinelUri(master, nodes, password, timeoutMs);
        }
        return standaloneUri(env.getOrDefault("REDIS_HOST", "localhost"),
                parsePort(env.getOrDefault("REDIS_PORT", "6379")), password, timeoutMs);
    }

    static RedisURI uriFrom(RedisProperties props) {
        if (props.isSentinelMode()) {
            String nodes = props.getSentinelNodes() == null || props.getSentinelNodes().isBlank()
                    ? "localhost:26379" : props.getSentinelNodes();
            return sentinelUri(props.getSentinelMaster(), nodes, props.getPassword(), props.getTimeoutMs());
        }
        return standaloneUri(props.getHost(), props.getPort(), props.getPassword(), props.getTimeoutMs());
    }

    static RedisURI standaloneUri(String host, int port, String password, int timeoutMs) {
        RedisURI.Builder b = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofMillis(Math.max(1, timeoutMs)));
        if (password != null && !password.isBlank()) b = b.withPassword((CharSequence) password);
        return b.build();
    }

    static RedisURI sentinelUri(String master, String nodes, String password, int timeoutMs) {
        RedisURI.Builder b = RedisURI.builder().withSentinelMasterId(master);
        for (String node : nodes.split(",")) {
            node = node.strip();
            if (node.isEmpty()) continue;
            int c = node.lastIndexOf(':');
            if (c > 0) {
                b = b.withSentinel(node.substring(0, c), parsePort(node.substring(c + 1)));
            } else {
                b = b.withSentinel(node, 26379);
            }
        }
        if (password != null && !password.isBlank()) b = b.withPassword((CharSequence) password);
        return b.withTimeout(Duration.ofMillis(Math.max(1, timeoutMs))).build();
    }

    // ── Pool config ─────────────────────────────────────────────────────────────

    static GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolConfig(RedisProperties.Pool pool) {
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> cfg = new GenericObjectPoolConfig<>();
        cfg.setMaxTotal(pool.getMaxTotal());
        cfg.setMaxIdle(pool.getMaxIdle());
        cfg.setMinIdle(pool.getMinIdle());
        cfg.setTestOnBorrow(pool.isTestOnBorrow());
        cfg.setBlockWhenExhausted(true);
        cfg.setMaxWait(Duration.ofMillis(pool.getMaxWaitMs()));
        cfg.setTestWhileIdle(true);
        cfg.setNumTestsPerEvictionRun(-1);
        cfg.setTimeBetweenEvictionRuns(Duration.ofMillis(30_000L));
        cfg.setMinEvictableIdleDuration(Duration.ofMillis(60_000L));
        return cfg;
    }

    static RedisProperties.Pool defaultPoolKnobs(Map<String, String> env) {
        RedisProperties.Pool p = new RedisProperties.Pool();
        p.setMaxTotal(readPositiveInt(env, "REDIS_POOL_MAX_TOTAL", DEFAULT_MAX_TOTAL));
        p.setMaxIdle(readPositiveInt(env, "REDIS_POOL_MAX_IDLE", DEFAULT_MAX_IDLE));
        p.setMinIdle(readNonNegativeInt(env, "REDIS_POOL_MIN_IDLE", DEFAULT_MIN_IDLE));
        p.setMaxWaitMs(readPositiveInt(env, "REDIS_POOL_MAX_WAIT_MS", DEFAULT_MAX_WAIT_MS));
        p.setTestOnBorrow(Boolean.parseBoolean(env.getOrDefault("REDIS_POOL_TEST_ON_BORROW", "true")));
        return p;
    }

    static int parsePort(String value) {
        if (value == null) return 6379;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 6379;
        }
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
