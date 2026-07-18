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
import java.time.Duration;

/**
 * {@link RedisExecutor} backed by Lettuce: one shared thread-safe connection for
 * normal sync commands and a commons-pool2 pool of dedicated connections for
 * pipelined batches (auto-flush is toggled per-connection, so pipelines must not
 * share the primary connection).
 *
 * <p>The shared connection and pooled connections are established <em>lazily</em>
 * on first use, mirroring the previous {@code JedisPool} behaviour: constructing
 * an executor against a down Redis never fails, so startup paths that build
 * stores up-front (e.g. the model-serving recall infra) keep working and only
 * fail — fast — at request time, where callers fall back.
 */
public final class LettuceRedisExecutor implements RedisExecutor {

    private final RedisClient client;
    private final GenericObjectPool<StatefulRedisConnection<String, String>> pool;
    private final boolean ownsClient;

    private volatile StatefulRedisConnection<String, String> shared;
    private final Object sharedLock = new Object();

    public LettuceRedisExecutor(RedisClient client,
                                GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolCfg,
                                boolean ownsClient) {
        this.client = client;
        this.ownsClient = ownsClient;
        // Pool creation does not open connections — they are created lazily on borrow.
        this.pool = ConnectionPoolSupport.createGenericObjectPool(
                () -> client.connect(StringCodec.UTF8), poolCfg);
    }

    /** Lazily opens the shared connection on first use (double-checked locking). */
    private StatefulRedisConnection<String, String> shared() {
        StatefulRedisConnection<String, String> current = shared;
        if (current == null) {
            synchronized (sharedLock) {
                current = shared;
                if (current == null) {
                    current = client.connect(StringCodec.UTF8);
                    shared = current;
                }
            }
        }
        return current;
    }

    @Override
    public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
        return fn.apply(shared().sync());
    }

    @Override
    public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
        return execute(fn); // single-endpoint executor reads from the same connection
    }

    @Override
    public <T> T executePrimaryRead(Function<RedisCommands<String, String>, T> fn, Duration timeout) {
        StatefulRedisConnection<String, String> conn = null;
        try {
            conn = pool.borrowObject(Math.max(1L, timeout.toMillis()));
            RedisCommands<String, String> commands = conn.sync();
            commands.setTimeout(timeout);
            return fn.apply(commands);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Timed primary Redis read failed", e);
        } finally {
            if (conn != null) pool.returnObject(conn);
        }
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
        StatefulRedisConnection<String, String> current = shared;
        if (current != null) current.close();
        if (ownsClient) client.shutdown();
    }
}
