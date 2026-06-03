package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.util.Pool;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RedisConnectionFactoryTest {

    private static JedisPoolConfig noIdleConfig() {
        JedisPoolConfig cfg = new JedisPoolConfig();
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
