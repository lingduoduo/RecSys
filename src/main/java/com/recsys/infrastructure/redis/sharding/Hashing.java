package com.recsys.infrastructure.redis.sharding;

import java.nio.charset.StandardCharsets;

/**
 * Shared deterministic hashing primitives for shard placement and stable bucketing.
 *
 * Do not change these constants, byte handling, or fmix64 operations without an explicit
 * remapping/bucketing migration plan.
 */
public final class Hashing {

    private static final long FNV_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_64_PRIME = 0x100000001b3L;

    private Hashing() {}

    public static long fnv1a64(byte[] data) {
        long h = FNV_64_OFFSET_BASIS;
        for (byte b : data) {
            h ^= (b & 0xffL);
            h *= FNV_64_PRIME;
        }
        return h;
    }

    public static long fnv1a64(String value) {
        return fnv1a64(value.getBytes(StandardCharsets.UTF_8));
    }

    public static long fmix64(long value) {
        long h = value;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
