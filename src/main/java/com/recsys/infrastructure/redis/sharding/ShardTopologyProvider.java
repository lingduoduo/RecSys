package com.recsys.infrastructure.redis.sharding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Holds the in-memory, periodically-refreshed view of the shard topology. Reads are lock-free
 * (a single volatile reference to an immutable {@link Snapshot}); a refresh atomically swaps it.
 * On any refresh failure the last-good snapshot is retained — topology I/O never breaks the
 * request path.
 */
public final class ShardTopologyProvider {

    private static final Logger log = LoggerFactory.getLogger(ShardTopologyProvider.class);

    private final ShardTopologyStore store;
    private final int vnodes;
    private final int initialShardCount;
    private final long refreshMs;
    private final LongSupplier clockMs;

    private volatile Snapshot snapshot;          // null until first successful refresh / fixed()
    private ScheduledExecutorService scheduler;

    public ShardTopologyProvider(ShardTopologyStore store, int vnodes, int initialShardCount,
                                 long refreshMs, LongSupplier clockMs) {
        this.store = store;
        this.vnodes = vnodes;
        this.initialShardCount = initialShardCount;
        this.refreshMs = refreshMs;
        this.clockMs = clockMs;
    }

    private ShardTopologyProvider(ShardTopology fixedCurrent) {
        this.store = null; this.vnodes = fixedCurrent.vnodes();
        this.initialShardCount = fixedCurrent.shardCount();
        this.refreshMs = 0L; this.clockMs = () -> 0L;
        this.snapshot = new Snapshot(fixedCurrent, null, Long.MIN_VALUE);
    }

    /** Constant version-1 provider with no Redis/refresh — for tests and non-dynamic wiring. */
    public static ShardTopologyProvider fixed(ConsistentHashRing ring) {
        return new ShardTopologyProvider(
                new ShardTopology(1, ring.shardCount(), ConsistentHashRing.DEFAULT_VIRTUAL_NODES, 0L));
    }

    /** Constant provider pinned at an explicit version — test/helper use. */
    public static ShardTopologyProvider fixedAtVersion(int version, int shardCount, int vnodes) {
        return new ShardTopologyProvider(new ShardTopology(version, shardCount, vnodes, 0L));
    }

    public void start() {
        if (store != null) {
            try { store.bootstrap(initialShardCount, vnodes, clockMs.getAsLong()); }
            catch (Exception e) { log.warn("topology bootstrap failed (will retry on refresh): {}", e.toString()); }
        }
        refresh();
        if (refreshMs > 0 && store != null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "shard-topology-refresh");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::refresh, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
        }
    }

    public void refresh() {
        if (store == null) return; // fixed provider
        try {
            ShardTopologyStore.Snapshot s = store.load();
            if (s == null) return;  // not yet bootstrapped — keep last-good
            ShardTopology current = new ShardTopology(s.version(), s.shardCount(), s.vnodes(), s.createdAtMs());
            ShardTopology previous = null;
            long prevExpiresAtMs = Long.MIN_VALUE;
            if (s.prevVersion() != null && s.prevShardCount() != null && s.prevExpiresAtMs() != null) {
                previous = new ShardTopology(s.prevVersion(), s.prevShardCount(), s.vnodes(), s.createdAtMs());
                prevExpiresAtMs = s.prevExpiresAtMs();
            }
            this.snapshot = new Snapshot(current, previous, prevExpiresAtMs);
        } catch (Exception e) {
            log.warn("topology refresh failed — keeping last-good snapshot: {}", e.toString());
        }
    }

    public ShardTopology current() {
        Snapshot s = snapshot;
        if (s == null) throw new IllegalStateException("topology not initialized — call start() first");
        return s.current;
    }

    public ShardTopology previousIfActive() {
        Snapshot s = snapshot;
        if (s == null || s.previous == null) return null;
        return clockMs.getAsLong() < s.prevExpiresAtMs ? s.previous : null;
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** Immutable triple swapped atomically on refresh. */
    private record Snapshot(ShardTopology current, ShardTopology previous, long prevExpiresAtMs) {}
}
