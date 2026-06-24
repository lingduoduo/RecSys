package com.recsys.infrastructure.redis.sharding;

import java.nio.charset.StandardCharsets;

/**
 * 64-bit FNV-1a hash. The single source of FNV-1a in the codebase — used by
 * {@link ConsistentHashRing} (shard placement) and by StableBucketer (A/B bucketing,
 * which then applies its own murmur3 fmix64 finalizer).
 *
 * Do not change the constants or byte handling: shard placement and A/B bucketing
 * both depend on the exact output.
 */
public final class Fnv1a {

    private Fnv1a() {}

    public static long hash(byte[] data) {
        long h = 0xcbf29ce484222325L;          // FNV-1a 64-bit offset basis
        for (byte b : data) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;               // FNV-1a 64-bit prime
        }
        return h;
    }

    public static long hash(String s) {
        return hash(s.getBytes(StandardCharsets.UTF_8));
    }
}
