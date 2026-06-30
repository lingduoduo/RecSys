package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.LettuceRedisExecutor;
import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class RedisShardingTestBase {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;

    /** Single-endpoint executor backed by the test container; used for both reads and writes. */
    protected static RedisExecutor exec;

    @BeforeAll
    static void startRedis() {
        client = RedisClient.create(
                RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection = client.connect(StringCodec.UTF8);
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> cfg =
                new GenericObjectPoolConfig<>();
        exec = new LettuceRedisExecutor(client, connection, cfg, true);
    }

    @AfterAll
    static void stopRedis() {
        if (exec != null) exec.close();
    }

    @AfterEach
    void flushRedis() {
        cmd().flushall();
    }

    /** Convenience accessor for raw sync Redis commands in test assertions/setup. */
    protected static RedisCommands<String, String> cmd() {
        return exec.execute(c -> c);
    }
}
