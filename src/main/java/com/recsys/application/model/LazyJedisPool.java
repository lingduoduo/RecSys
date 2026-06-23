package com.recsys.application.model;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.function.Supplier;

/**
 * Lazily initializes a Jedis {@link Pool} from a {@link Supplier} on first use
 * (double-checked locking) and lends connections from it. Shared by the
 * Redis-backed token services so the pool wiring lives in one place.
 */
public final class LazyJedisPool implements AutoCloseable {

    private final Supplier<Pool<Jedis>> poolFactory;
    private volatile Pool<Jedis> pool;

    public LazyJedisPool(Supplier<Pool<Jedis>> poolFactory) {
        this.poolFactory = poolFactory;
    }

    /** Borrows a connection, creating the underlying pool on first call. */
    public Jedis resource() {
        Pool<Jedis> current = pool;
        if (current == null) {
            synchronized (this) {
                current = pool;
                if (current == null) {
                    current = poolFactory.get();
                    pool = current;
                }
            }
        }
        return current.getResource();
    }

    @Override
    public void close() {
        Pool<Jedis> current = pool;
        if (current != null) {
            current.close();
        }
    }
}
