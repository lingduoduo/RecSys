package com.recsys.infrastructure.redis;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link RedisExecutor} decorator that builds the underlying executor from a
 * {@link Supplier} on first use (double-checked locking) and delegates to it.
 *
 * <p>Defers the cost of creating the real executor — the Lettuce client and its
 * commons-pool2 pool (whose idle-eviction sweep runs a background thread) — until
 * a Redis command is actually issued, so Redis-backed components that may never be
 * exercised cost nothing at startup.
 */
public final class LazyRedisExecutor implements RedisExecutor {

    private final Supplier<RedisExecutor> executorFactory;
    private volatile RedisExecutor delegate;

    public LazyRedisExecutor(Supplier<RedisExecutor> executorFactory) {
        this.executorFactory = executorFactory;
    }

    @Override
    public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
        return delegate().execute(fn);
    }

    @Override
    public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
        return delegate().executeRead(fn);
    }

    @Override
    public void executePipelined(Consumer<StatefulRedisConnection<String, String>> fn) {
        delegate().executePipelined(fn);
    }

    private RedisExecutor delegate() {
        RedisExecutor current = delegate;
        if (current == null) {
            synchronized (this) {
                current = delegate;
                if (current == null) {
                    current = executorFactory.get();
                    delegate = current;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        RedisExecutor current = delegate;
        if (current != null) {
            current.close();
        }
    }
}
