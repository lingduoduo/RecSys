package com.recsys.infrastructure.resilience;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Thread-safe Bloom filter for cache penetration protection.
 *
 * {@link #mightContain} returning {@code false} guarantees the ID was never added.
 * Returning {@code true} means it was probably added (bounded false-positive rate).
 *
 * Integration pattern: after warm-up, all valid IDs are in the filter. Before hitting
 * Redis for an unknown ID, call {@link #mightContain}: if {@code false}, the ID is
 * definitively absent — skip the Redis round-trip entirely.
 * Call {@link #add} on every successful embedding write.
 */
public final class BloomFilterGuard {

    private final AtomicLongArray bits;
    private final int numBits;
    private final int numHashFunctions;

    /**
     * @param expectedInsertions estimated number of distinct IDs that will be added
     * @param falsePositiveRate  target false-positive probability (e.g., {@code 0.01} for 1%)
     */
    public BloomFilterGuard(int expectedInsertions, double falsePositiveRate) {
        this.numBits = optimalNumBits(expectedInsertions, falsePositiveRate);
        this.numHashFunctions = optimalNumHashFunctions(expectedInsertions, numBits);
        this.bits = new AtomicLongArray((numBits + 63) / 64);
    }

    /** Adds {@code id} to the filter. Thread-safe. */
    public void add(int id) {
        for (int i = 0; i < numHashFunctions; i++) {
            int bitIndex = Math.floorMod(hash(id, i), numBits);
            bits.updateAndGet(bitIndex >>> 6, word -> word | (1L << (bitIndex & 63)));
        }
    }

    /**
     * Returns {@code false} if {@code id} was definitely never added.
     * Returns {@code true} if {@code id} was probably added (may be a false positive).
     * Thread-safe.
     */
    public boolean mightContain(int id) {
        for (int i = 0; i < numHashFunctions; i++) {
            int bitIndex = Math.floorMod(hash(id, i), numBits);
            if ((bits.get(bitIndex >>> 6) & (1L << (bitIndex & 63))) == 0L) {
                return false;
            }
        }
        return true;
    }

    // Double-hashing: h_i(x) = hash(id, i) produces k independent bit probes
    // using a 64-bit multiplicative mix to spread integer IDs across the bit array.
    private static int hash(int id, int seed) {
        long h = (long) id * 0x9e3779b97f4a7c15L ^ (long) seed * 0x6c62272e07bb0142L;
        h ^= h >>> 30;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27;
        h *= 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return (int) h;
    }

    // m = -n * ln(p) / (ln(2)^2)
    private static int optimalNumBits(int n, double p) {
        return Math.max(64, (int) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2))));
    }

    // k = (m/n) * ln(2)
    private static int optimalNumHashFunctions(int n, int m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    int bitCount() { return numBits; }
    int hashFunctionCount() { return numHashFunctions; }
}
