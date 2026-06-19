package com.recsys.model.service;

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

    // FNV-1a accumulation followed by the murmur3 fmix64 finalizer — good avalanche, no dependency.
    private static long hash64(byte[] data) {
        long h = 0xcbf29ce484222325L;          // FNV-1a 64-bit offset basis
        for (byte b : data) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;               // FNV-1a 64-bit prime
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;   // murmur3 x64-128 finalizer constant (avalanche-equivalent fmix64 variant)
        h ^= (h >>> 33);
        return h;
    }
}
