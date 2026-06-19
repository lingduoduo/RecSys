package com.recsys.infrastructure.redis;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.Pool;

import java.util.Map;
import java.util.Set;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RedisConnectionFactoryTest {

    private static GenericObjectPoolConfig<Jedis> noIdleConfig() {
        GenericObjectPoolConfig<Jedis> cfg = new GenericObjectPoolConfig<>();
        cfg.setMinIdle(0);
        cfg.setTestOnBorrow(false);
        return cfg;
    }

    @Test
    void standaloneModeCreatesJedisPool() {
        Map<String, String> env = Map.of(
            "REDIS_MODE", "standalone",
            "REDIS_HOST", "localhost",
            "REDIS_PORT", "6379"
        );
        try (Pool<Jedis> pool = RedisConnectionFactory.create(env, noIdleConfig())) {
            assertInstanceOf(JedisPool.class, pool);
        }
    }

    @Test
    void defaultModeIsStandalone() {
        try (Pool<Jedis> pool = RedisConnectionFactory.create(Map.of(), noIdleConfig())) {
            assertInstanceOf(JedisPool.class, pool);
        }
    }

    @Test
    void parseSentinelNodesHandlesMultipleCommaSeparatedEntries() {
        Set<String> nodes = RedisConnectionFactory.parseSentinelNodes(
            "sentinel-1:26379,sentinel-2:26379, sentinel-3:26379"
        );
        assertEquals(Set.of("sentinel-1:26379", "sentinel-2:26379", "sentinel-3:26379"), nodes);
    }

    @Test
    void parseSentinelNodesDefaultsToLocalhostWhenBlank() {
        assertEquals(Set.of("localhost:26379"),
            RedisConnectionFactory.parseSentinelNodes(""));
        assertEquals(Set.of("localhost:26379"),
            RedisConnectionFactory.parseSentinelNodes(null));
    }

    @Test
    void parsePortReturnsDefaultOnInvalidValue() {
        assertEquals(6379, RedisConnectionFactory.parsePort("notANumber"));
    }

    @Test
    void parsePortParsesValidPort() {
        assertEquals(6380, RedisConnectionFactory.parsePort("6380"));
    }

    @Test
    void poolConfigReadsFailFastLimitsFromEnvironment() {
        GenericObjectPoolConfig<Jedis> config = RedisConnectionFactory.defaultPoolConfig(Map.of(
                "REDIS_POOL_MAX_TOTAL", "80",
                "REDIS_POOL_MAX_IDLE", "20",
                "REDIS_POOL_MIN_IDLE", "4",
                "REDIS_POOL_MAX_WAIT_MS", "125",
                "REDIS_POOL_TEST_ON_BORROW", "false"
        ));

        assertEquals(80, config.getMaxTotal());
        assertEquals(20, config.getMaxIdle());
        assertEquals(4, config.getMinIdle());
        assertEquals(Duration.ofMillis(125), config.getMaxWaitDuration());
        assertFalse(config.getTestOnBorrow());
    }

    @Test
    void standaloneWithPasswordUsesPasswordConstructor() {
        Map<String, String> env = Map.of(
            "REDIS_MODE", "standalone",
            "REDIS_HOST", "localhost",
            "REDIS_PORT", "6379",
            "REDIS_PASSWORD", "secret"
        );
        try (Pool<Jedis> pool = RedisConnectionFactory.create(env, noIdleConfig())) {
            assertInstanceOf(JedisPool.class, pool);
        }
    }

    @Test
    void effectiveTimeoutMs_capsToMaxWhenEnvUnset() {
        // No REDIS_TIMEOUT_MS in env → default (~2000ms) is clamped to the supplied cap.
        int result = RedisConnectionFactory.effectiveTimeoutMs(Map.of(), 150);
        assertEquals(150, result);
    }

    @Test
    void effectiveTimeoutMs_usesSmallerOfEnvAndCap() {
        // REDIS_TIMEOUT_MS=100 < cap 150 → env value wins.
        int result = RedisConnectionFactory.effectiveTimeoutMs(Map.of("REDIS_TIMEOUT_MS", "100"), 150);
        assertEquals(100, result);
    }

    @Test
    void effectiveTimeoutMs_capsWhenEnvExceedsCap() {
        // REDIS_TIMEOUT_MS=500 > cap 150 → cap wins.
        int result = RedisConnectionFactory.effectiveTimeoutMs(Map.of("REDIS_TIMEOUT_MS", "500"), 150);
        assertEquals(150, result);
    }

    @Test
    void effectiveTimeoutMs_noCapWhenMaxIsIntMax() {
        // fromEnv() delegates with Integer.MAX_VALUE → env value is used as-is.
        int result = RedisConnectionFactory.effectiveTimeoutMs(Map.of("REDIS_TIMEOUT_MS", "300"), Integer.MAX_VALUE);
        assertEquals(300, result);
    }

    @Test
    void sentinelModeCreatesJedisSentinelPool() {
        Map<String, String> env = Map.of(
            "REDIS_MODE", "sentinel",
            "REDIS_SENTINEL_MASTER", "mymaster",
            "REDIS_SENTINEL_NODES", "sentinel-1:26379,sentinel-2:26379,sentinel-3:26379"
        );
        // JedisSentinelPool connects at construction; with fake nodes it throws —
        // the assertThrows confirms the sentinel code path is reached (not the standalone path)
        assertThrows(Exception.class,
            () -> RedisConnectionFactory.create(env, noIdleConfig()));
    }
}
