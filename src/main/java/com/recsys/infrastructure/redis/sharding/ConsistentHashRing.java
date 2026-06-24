package com.recsys.infrastructure.redis.sharding;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable consistent-hash ring mapping device/user IDs to shard indices.
 *
 * Uses FNV-1a (64-bit) for hashing and virtual nodes for uniform distribution.
 * Each physical shard gets {@code virtualNodesPerShard} virtual nodes spread
 * across the hash space by hashing "v{i}:{shardIndex}" strings.
 *
 * Thread-safe after construction — all state is final.
 */
public final class ConsistentHashRing {

    static final int DEFAULT_VIRTUAL_NODES = 150;

    private final TreeMap<Long, Integer> ring = new TreeMap<>();
    private final int shardCount;

    public ConsistentHashRing(int shardCount, int virtualNodesPerShard) {
        if (shardCount < 1) throw new IllegalArgumentException("shardCount must be >= 1");
        if (virtualNodesPerShard < 1) throw new IllegalArgumentException("virtualNodesPerShard must be >= 1");
        this.shardCount = shardCount;

        for (int shard = 0; shard < shardCount; shard++) {
            for (int v = 0; v < virtualNodesPerShard; v++) {
                long hash = Fnv1a.hash("v" + v + ":" + shard);
                ring.put(hash, shard);
            }
        }
    }

    /** Returns the shard index for the given device/user ID. Lock-free after construction. */
    public int shardFor(String deviceId) {
        long hash = Fnv1a.hash(deviceId);
        Map.Entry<Long, Integer> entry = ring.ceilingEntry(hash);
        return (entry != null ? entry : ring.firstEntry()).getValue();
    }

    public int shardCount() { return shardCount; }

    /** Returns device count per shard. Useful for diagnosing hot-shard imbalance. */
    public Map<Integer, Integer> distribution(Collection<String> deviceIds) {
        Map<Integer, Integer> dist = new HashMap<>();
        for (int i = 0; i < shardCount; i++) dist.put(i, 0);
        for (String id : deviceIds) dist.merge(shardFor(id), 1, Integer::sum);
        return dist;
    }

}
