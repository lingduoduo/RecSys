package com.recsys.application.experiment;

import com.recsys.infrastructure.redis.sharding.Hashing;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic, well-distributed hashing of {@code userId:layer} into a fixed keyspace.
 * Replaces {@code String.hashCode() % trafficSplitNumber}: a stable hash plus a fixed keyspace
 * lets traffic allocations move as ranges without reshuffling users (see ABTestService).
 * Stable across JVMs (no reliance on JVM-specific hashing).
 */
public final class StableBucketer {

    public static final int KEYSPACE = 10_000;

    private StableBucketer() {}

    /** Returns the keyspace slot in {@code [0, KEYSPACE)} for the given user and layer. */
    public static int slot(String userId, String layerName) {
        String key = (userId == null ? "" : userId) + ":" + (layerName == null ? "" : layerName);
        long h = hash64(key.getBytes(StandardCharsets.UTF_8));
        return (int) Long.remainderUnsigned(h, KEYSPACE);
    }

    // FNV-1a accumulation followed by the murmur3 fmix64 finalizer.
    private static long hash64(byte[] data) {
        return Hashing.fmix64(Hashing.fnv1a64(data));
    }
}
