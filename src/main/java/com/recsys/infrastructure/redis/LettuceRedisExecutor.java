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
import java.util.function.LongSupplier;

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
    private final LongSupplier monotonicNanos;

    private volatile StatefulRedisConnection<String, String> shared;
    private final Object sharedLock = new Object();

    public LettuceRedisExecutor(RedisClient client,
                                GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolCfg,
                                boolean ownsClient) {
        this(client, ConnectionPoolSupport.createGenericObjectPool(
                () -> client.connect(StringCodec.UTF8), poolCfg), ownsClient, System::nanoTime);
    }

    LettuceRedisExecutor(RedisClient client,
                         GenericObjectPool<StatefulRedisConnection<String, String>> pool,
                         boolean ownsClient,
                         LongSupplier monotonicNanos) {
        this.client = client;
        this.ownsClient = ownsClient;
        this.pool = pool;
        this.monotonicNanos = monotonicNanos;
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
        long budgetNanos = timeout.toNanos();
        if (budgetNanos <= 0) throw new IllegalArgumentException("timeout must be positive");
        long deadline = monotonicNanos.getAsLong() + budgetNanos;
        Duration previousTimeout = null;
        try {
            long borrowMillis = Math.max(1L, (budgetNanos + 999_999L) / 1_000_000L);
            conn = pool.borrowObject(borrowMillis);
            long remainingNanos = deadline - monotonicNanos.getAsLong();
            if (remainingNanos <= 0) throw new IllegalStateException("Timed primary Redis read deadline exceeded");
            RedisCommands<String, String> commands = conn.sync();
            previousTimeout = conn.getTimeout();
            conn.setTimeout(Duration.ofNanos(Math.max(1L, remainingNanos)));
            return fn.apply(commands);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Timed primary Redis read failed", e);
        } finally {
            if (conn != null) {
                try {
                    if (previousTimeout != null) conn.setTimeout(previousTimeout);
                    pool.returnObject(conn);
                } catch (RuntimeException restoreFailure) {
                    try { pool.invalidateObject(conn); } catch (Exception ignored) { }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>A connection whose pipeline lifecycle was interrupted is <em>destroyed</em>, not
     * returned to the pool. Two failure modes make reuse unsafe:
     *
     * <ul>
     *   <li>If the callback throws after queueing commands but before {@code flushCommands()},
     *       those commands are still buffered — {@code setAutoFlushCommands(true)} does not
     *       flush them. Returning the connection would let the next borrower's flush execute
     *       a failed batch's writes against an unrelated request.</li>
     *   <li>If {@code setAutoFlushCommands(false)} itself throws, auto-flush is never
     *       restored, and the next borrower's commands would buffer and never flush.</li>
     * </ul>
     *
     * <p>The cost is one dropped connection per failed pipeline, which is the right trade
     * against silently replaying a write the caller was told had failed.
     */
    @Override
    public void executePipelined(Consumer<StatefulRedisConnection<String, String>> fn) {
        StatefulRedisConnection<String, String> conn = null;
        boolean corrupt = false;
        try {
            conn = pool.borrowObject();
            conn.setAutoFlushCommands(false);
            try {
                fn.accept(conn);
            } finally {
                conn.setAutoFlushCommands(true);
            }
        } catch (RuntimeException e) {
            corrupt = true;
            throw e;
        } catch (Exception e) {
            corrupt = true;
            throw new IllegalStateException("Pipelined Redis batch failed", e);
        } finally {
            if (conn != null) {
                if (corrupt) {
                    invalidateQuietly(conn);
                } else {
                    try {
                        pool.returnObject(conn);
                    } catch (RuntimeException returnFailure) {
                        invalidateQuietly(conn);
                    }
                }
            }
        }
    }

    /** Destroying a pooled connection is best-effort: never mask the original outcome. */
    private void invalidateQuietly(StatefulRedisConnection<String, String> conn) {
        try {
            pool.invalidateObject(conn);
        } catch (Exception ignored) {
            // The pool could not destroy it; there is nothing further we can do here.
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
