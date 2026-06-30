package com.recsys.infrastructure.redis;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.Closeable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstraction over a Redis client that preserves the "borrow a connection, run a
 * few commands, return it" ergonomics of the previous {@code Pool<Jedis>} usage,
 * while hiding the Lettuce connection model from call sites.
 *
 * <p>Implementations are thread-safe. A single executor wraps one logical Redis
 * endpoint (standalone, sentinel-resolved primary, or one replica).
 */
public interface RedisExecutor extends Closeable {

    /** Runs sync commands on the shared (thread-safe) primary connection. */
    <T> T execute(Function<RedisCommands<String, String>, T> fn);

    /**
     * Like {@link #execute} but routed to a replica when one is available.
     * Single-endpoint implementations delegate to {@link #execute}.
     */
    <T> T executeRead(Function<RedisCommands<String, String>, T> fn);

    /**
     * Runs a pipelined batch on a dedicated pooled connection with auto-flush
     * disabled by the adapter. The callback issues async commands via
     * {@code conn.async()}, then calls {@code conn.flushCommands()} and awaits the
     * resulting futures (only the callback knows its timeout budget and which
     * futures to await).
     */
    void executePipelined(Consumer<StatefulRedisConnection<String, String>> fn);

    @Override
    void close();
}
