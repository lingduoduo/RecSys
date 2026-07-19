package com.recsys.infrastructure.redis;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.function.Consumer;
import java.util.function.Function;
import java.time.Duration;
import java.util.Optional;

/**
 * A {@link RedisExecutor} that splits reads from writes across a
 * {@link RedisReadReplicaRouter}: mutations and pipelines go to the primary
 * (write leader), while {@link #executeRead} is routed to a read replica when one
 * is configured (falling back to the primary otherwise).
 *
 * <p>This is the adapter that lets existing call sites — which already distinguish
 * {@code execute} (writes) from {@code executeRead} (reads) — transparently route
 * reads to replicas without any further code change. When no replicas are
 * configured, the wrapped router returns the primary for reads too, so behavior is
 * identical to a single-endpoint executor.
 */
public final class RoutingRedisExecutor implements RedisExecutor {

    private final RedisReadReplicaRouter router;

    public RoutingRedisExecutor(RedisReadReplicaRouter router) {
        this.router = router;
    }

    @Override
    public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
        return router.writable().execute(fn);
    }

    @Override
    public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
        return router.readable().executeRead(fn);
    }

    @Override public <T> Optional<T> executeReplicaRead(Function<RedisCommands<String, String>, T> fn) {
        return router.probeReadable().map(replica -> replica.executeRead(fn));
    }

    @Override
    public <T> T executePrimaryRead(Function<RedisCommands<String, String>, T> fn) {
        // Read on the primary/writable node via its read path — never a replica.
        return router.writable().executeRead(fn);
    }

    @Override
    public <T> T executePrimaryRead(Function<RedisCommands<String, String>, T> fn, Duration timeout) {
        return router.writable().executePrimaryRead(fn, timeout);
    }

    @Override
    public void executePipelined(Consumer<StatefulRedisConnection<String, String>> fn) {
        router.writable().executePipelined(fn);
    }

    @Override
    public void executeReadPipelined(Consumer<StatefulRedisConnection<String, String>> fn) {
        router.readable().executeReadPipelined(fn);
    }

    @Override
    public void close() {
        router.close();
    }
}
