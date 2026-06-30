package com.recsys.application.model;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lazily initializes a {@link RedisExecutor} from a {@link Supplier} on first use
 * (double-checked locking) and delegates command execution to it. Shared by the
 * Redis-backed token services so the executor wiring lives in one place.
 */
public final class LazyRedisExecutor implements AutoCloseable {

    private final Supplier<RedisExecutor> executorFactory;
    private volatile RedisExecutor executor;

    public LazyRedisExecutor(Supplier<RedisExecutor> executorFactory) {
        this.executorFactory = executorFactory;
    }

    /** Runs sync commands on the shared connection, creating the executor on first call. */
    public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
        return executor().execute(fn);
    }

    /** Like {@link #execute} but routed to a replica when one is available. */
    public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
        return executor().executeRead(fn);
    }

    /** Runs a pipelined batch on a dedicated pooled connection. */
    public void executePipelined(Consumer<StatefulRedisConnection<String, String>> fn) {
        executor().executePipelined(fn);
    }

    private RedisExecutor executor() {
        RedisExecutor current = executor;
        if (current == null) {
            synchronized (this) {
                current = executor;
                if (current == null) {
                    current = executorFactory.get();
                    executor = current;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        RedisExecutor current = executor;
        if (current != null) {
            current.close();
        }
    }
}
