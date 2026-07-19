package com.recsys.infrastructure.redis;

import java.io.Closeable;
import java.util.List;
import java.util.Optional;

/**
 * Routes Redis operations to the correct {@link RedisExecutor} based on access
 * type and Availability Zone locality.
 *
 * <p><b>Write path</b>: always uses the primary executor (write leader).
 *
 * <p><b>Read path</b>: prefers the replica in the same AZ as this service
 * instance (set via the {@code AWS_AZ} environment variable).  Falls back to a
 * randomly selected replica, or to the primary when no replicas are configured.
 * This keeps reads available even if the primary AZ becomes unreachable, and
 * avoids cross-AZ data-transfer costs on the hot read path.
 *
 * <p>Lifecycle: call {@link #close()} once on shutdown to close all executors.
 */
public final class RedisReadReplicaRouter implements Closeable {

    /** One replica executor plus its declared Availability Zone label. */
    public record AzExecutor(RedisExecutor exec, String az) {}

    private final RedisExecutor primary;
    private final List<AzExecutor> replicas;
    private final String localAz;

    /**
     * @param primary  executor for the Redis write leader
     * @param replicas zero or more AZ-tagged read-replica executors
     * @param localAz  AZ of this service instance (e.g. {@code "us-east-1b"})
     */
    public RedisReadReplicaRouter(RedisExecutor primary,
                                  List<AzExecutor> replicas,
                                  String localAz) {
        this.primary  = primary;
        this.replicas = List.copyOf(replicas);
        this.localAz  = localAz == null ? "unknown" : localAz;
    }

    /** Returns the write executor (primary). Always use this for mutations. */
    public RedisExecutor writable() {
        return primary;
    }

    /**
     * Returns the best read executor for the current AZ.
     *
     * <ol>
     *   <li>Same-AZ replica — lowest latency, survives primary-AZ failure.</li>
     *   <li>Random replica — load-balances when no same-AZ replica exists.</li>
     *   <li>Primary — safe fallback when no replicas are configured.</li>
     * </ol>
     */
    public RedisExecutor readable() {
        if (replicas.isEmpty()) return primary;

        // prefer same-AZ replica
        for (AzExecutor ae : replicas) {
            if (localAz.equals(ae.az())) return ae.exec();
        }

        // Stable fallback: the configured order identifies one replica for the process lifetime.
        return replicas.get(0).exec();
    }

    /** Stable replica used by correlated health probes; never falls back to primary. */
    public Optional<RedisExecutor> probeReadable() {
        if (replicas.isEmpty()) return Optional.empty();
        for (AzExecutor ae : replicas) if (localAz.equals(ae.az())) return Optional.of(ae.exec());
        return Optional.of(replicas.get(0).exec());
    }

    /** Number of configured read replicas (0 when running without replicas). */
    public int replicaCount() {
        return replicas.size();
    }

    /** AZ this router considers local. */
    public String localAz() {
        return localAz;
    }

    @Override
    public void close() {
        primary.close();
        for (AzExecutor ae : replicas) ae.exec().close();
    }
}
