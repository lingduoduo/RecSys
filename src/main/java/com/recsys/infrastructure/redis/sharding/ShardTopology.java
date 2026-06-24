package com.recsys.infrastructure.redis.sharding;

/**
 * Immutable snapshot of one shard-topology generation: a version, a shard count, and the
 * consistent-hash ring built from them. Thread-safe (all fields final, ring immutable).
 */
public final class ShardTopology {

    private final int version;
    private final int shardCount;
    private final int vnodes;
    private final long createdAtMs;
    private final ConsistentHashRing ring;

    public ShardTopology(int version, int shardCount, int vnodes, long createdAtMs) {
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        this.version = version;
        this.shardCount = shardCount;          // ConsistentHashRing validates >= 1
        this.vnodes = vnodes;                  // ConsistentHashRing validates >= 1
        this.createdAtMs = createdAtMs;
        this.ring = new ConsistentHashRing(shardCount, vnodes);
    }

    public int version()      { return version; }
    public int shardCount()   { return shardCount; }
    public int vnodes()       { return vnodes; }
    public long createdAtMs() { return createdAtMs; }
    public ConsistentHashRing ring() { return ring; }

    public int shardFor(String deviceId) { return ring.shardFor(deviceId); }
}
