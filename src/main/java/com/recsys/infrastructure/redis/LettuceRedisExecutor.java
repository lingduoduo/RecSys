package com.recsys.infrastructure.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.support.ConnectionPoolSupport;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link RedisExecutor} backed by Lettuce: one shared thread-safe connection for
 * normal sync commands and a commons-pool2 pool of dedicated connections for
 * pipelined batches (auto-flush is toggled per-connection, so pipelines must not
 * share the primary connection).
 */
public final class LettuceRedisExecutor implements RedisExecutor {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> shared;
    private final GenericObjectPool<StatefulRedisConnection<String, String>> pool;
    private final boolean ownsClient;

    public LettuceRedisExecutor(RedisClient client,
                                StatefulRedisConnection<String, String> shared,
                                GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolCfg,
                                boolean ownsClient) {
        this.client = client;
        this.shared = shared;
        this.ownsClient = ownsClient;
        this.pool = ConnectionPoolSupport.createGenericObjectPool(
                () -> client.connect(StringCodec.UTF8), poolCfg);
    }

    @Override
    public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
        return fn.apply(shared.sync());
    }

    @Override
    public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
        return execute(fn); // single-endpoint executor reads from the same connection
    }

    @Override
    public void executePipelined(Consumer<StatefulRedisConnection<String, String>> fn) {
        StatefulRedisConnection<String, String> conn = null;
        try {
            conn = pool.borrowObject();
            conn.setAutoFlushCommands(false);
            try {
                fn.accept(conn);
            } finally {
                conn.setAutoFlushCommands(true);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Pipelined Redis batch failed", e);
        } finally {
            if (conn != null) pool.returnObject(conn);
        }
    }

    @Override
    public void close() {
        pool.close();
        shared.close();
        if (ownsClient) client.shutdown();
    }
}
