package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.Pool;

@Testcontainers
public abstract class RedisShardingTestBase {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    protected static Pool<Jedis> pool;

    @BeforeAll
    static void startRedis() {
        pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @AfterEach
    void flushRedis() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushAll();
        }
    }
}
