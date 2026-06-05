package com.recsys.infrastructure.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Routes Redis operations to the correct pool based on access type and
 * Availability Zone locality.
 *
 * <p><b>Write path</b>: always uses the primary pool (write leader).
 *
 * <p><b>Read path</b>: prefers the replica in the same AZ as this service
 * instance (set via the {@code AWS_AZ} environment variable).  Falls back to a
 * randomly selected replica, or to the primary when no replicas are configured.
 * This keeps reads available even if the primary AZ becomes unreachable, and
 * avoids cross-AZ data-transfer costs on the hot read path.
 *
 * <p>Lifecycle: call {@link #close()} once on shutdown to drain and close all
 * pools.
 */
public final class RedisReadReplicaRouter implements Closeable {

    /** One replica pool plus its declared Availability Zone label. */
    public record AzPool(Pool<Jedis> pool, String az) {}

    private final Pool<Jedis> primaryPool;
    private final List<AzPool> replicaPools;
    private final String localAz;

    /**
     * @param primaryPool  connection pool for the Redis write leader
     * @param replicaPools zero or more AZ-tagged read-replica pools
     * @param localAz      AZ of this service instance (e.g. {@code "us-east-1b"})
     */
    public RedisReadReplicaRouter(Pool<Jedis> primaryPool,
                                  List<AzPool> replicaPools,
                                  String localAz) {
        this.primaryPool  = primaryPool;
        this.replicaPools = List.copyOf(replicaPools);
        this.localAz      = localAz == null ? "unknown" : localAz;
    }

    /** Returns the write pool (primary). Always use this for mutations. */
    public Pool<Jedis> writablePool() {
        return primaryPool;
    }

    /**
     * Returns the best read pool for the current AZ.
     *
     * <ol>
     *   <li>Same-AZ replica — lowest latency, survives primary-AZ failure.</li>
     *   <li>Random replica — load-balances when no same-AZ replica exists.</li>
     *   <li>Primary — safe fallback when no replicas are configured.</li>
     * </ol>
     */
    public Pool<Jedis> readablePool() {
        if (replicaPools.isEmpty()) return primaryPool;

        // prefer same-AZ replica
        for (AzPool ap : replicaPools) {
            if (localAz.equals(ap.az())) return ap.pool();
        }

        // fall back to random replica
        return replicaPools.get(ThreadLocalRandom.current().nextInt(replicaPools.size())).pool();
    }

    /** Number of configured read replicas (0 when running without replicas). */
    public int replicaCount() {
        return replicaPools.size();
    }

    /** AZ this router considers local. */
    public String localAz() {
        return localAz;
    }

    @Override
    public void close() {
        primaryPool.close();
        for (AzPool ap : replicaPools) ap.pool().close();
    }
}
