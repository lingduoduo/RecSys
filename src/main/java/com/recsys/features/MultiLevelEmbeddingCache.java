package com.recsys.features;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explicit three-tier embedding cache: L1 (JVM hot-key heap) → L2 (Redis) → L3 (fallback).
 *
 * Tier layout:
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │ L1 — ConcurrentHashMap, bounded by {@code l1Capacity}.                     │
 * │      Hot-key promotion: on an L2 hit, the value is written to L1 if either  │
 * │      capacity allows it or the key is detected as hot by {@link HotKeyDetector}. │
 * │      No TTL; entries live until the cache fills up or the JVM restarts.    │
 * │                                                                             │
 * │ L2 — Any EmbeddingStore (typically RedisEmbeddingStore or                  │
 * │      LocalEmbeddingCache → Redis).  L2 exceptions are caught and logged;   │
 * │      execution falls through to L3 (graceful degradation).                 │
 * │                                                                             │
 * │ L3 — Optional fallback EmbeddingStore (e.g. file-system snapshot).         │
 * │      Used when L2 is unavailable.  May be {@code null}.                    │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * Per-tier CacheStats expose hit rates so operators can tune capacity and decide
 * whether a Redis cluster issue is causing abnormal L3 traffic.
 *
 * Write semantics: {@link #setEmbedding} writes to L1 and L2 (write-through).
 * L3 writes are attempted but failures are silently swallowed (L3 is read-only
 * for most deployments).
 */
public final class MultiLevelEmbeddingCache implements EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelEmbeddingCache.class);

    static final int DEFAULT_L1_CAPACITY = 10_000;

    // L1: JVM ConcurrentHashMap, capped at l1Capacity entries.
    private final ConcurrentHashMap<Integer, float[]> l1;
    private final int l1Capacity;

    // L2: typically Redis-backed (may throw on connection failure)
    private final EmbeddingStore l2;

    // L3: optional file-system or degraded fallback (null → no L3)
    private final EmbeddingStore l3;

    // Hot-key detector: decides whether a newly fetched ID should be promoted to L1.
    private final HotKeyDetector hotKeyDetector;

    // Per-tier counters
    private final AtomicLong l1Hits   = new AtomicLong();
    private final AtomicLong l2Hits   = new AtomicLong();
    private final AtomicLong l3Hits   = new AtomicLong();
    private final AtomicLong misses   = new AtomicLong();

    /** Full constructor — use {@link Builder} for ergonomic construction. */
    MultiLevelEmbeddingCache(EmbeddingStore l2, EmbeddingStore l3,
                              int l1Capacity, HotKeyDetector hotKeyDetector) {
        this.l2             = l2;
        this.l3             = l3;
        this.l1Capacity     = Math.max(1, l1Capacity);
        this.hotKeyDetector = hotKeyDetector;
        this.l1             = new ConcurrentHashMap<>(Math.min(l1Capacity, 1 << 14));
    }

    // ── Read path ──────────────────────────────────────────────────────────────────

    @Override
    public float[] getEmbedding(int id) {
        String hotKey = "id:" + id;
        hotKeyDetector.record(hotKey);

        // L1 — JVM hot-key cache (fastest, no network)
        float[] v = l1.get(id);
        if (v != null) { l1Hits.incrementAndGet(); return v; }

        // L2 — Redis (or wrapping LocalEmbeddingCache)
        try {
            v = l2.getEmbedding(id);
            if (v != null) {
                l2Hits.incrementAndGet();
                promoteToL1IfEligible(id, v, hotKey);
                return v;
            }
        } catch (Exception e) {
            log.warn("MultiLevelCache: L2 error for id={}, falling through to L3: {}", id, e.toString());
        }

        // L3 — fallback / file-system snapshot
        if (l3 != null) {
            try {
                v = l3.getEmbedding(id);
                if (v != null) {
                    l3Hits.incrementAndGet();
                    promoteToL1IfEligible(id, v, hotKey);
                    return v;
                }
            } catch (Exception e) {
                log.warn("MultiLevelCache: L3 error for id={}: {}", id, e.toString());
            }
        }

        misses.incrementAndGet();
        return null;
    }

    @Override
    public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();

        Map<Integer, float[]> result = new HashMap<>(ids.size() * 2);
        Set<Integer> l1Misses = new LinkedHashSet<>();

        // L1 batch check
        for (int id : ids) {
            hotKeyDetector.record("id:" + id);   // one string per id; recomputed on promotion only when L1 is full
            float[] v = l1.get(id);
            if (v != null) { l1Hits.incrementAndGet(); result.put(id, v); }
            else            { l1Misses.add(id); }
        }
        if (l1Misses.isEmpty()) return result;

        // L2 batch fetch for misses
        Set<Integer> l2Misses = new LinkedHashSet<>();
        try {
            Map<Integer, float[]> l2Result = l2.getEmbeddings(l1Misses);
            for (int id : l1Misses) {
                float[] v = l2Result.get(id);
                if (v != null) { l2Hits.incrementAndGet(); promoteToL1IfEligible(id, v, "id:" + id); result.put(id, v); }
                else           { l2Misses.add(id); }
            }
        } catch (Exception e) {
            log.warn("MultiLevelCache: L2 batch error, falling through to L3: {}", e.toString());
            l2Misses.addAll(l1Misses);
        }
        if (l2Misses.isEmpty()) return result;

        // L3 batch fetch for remaining misses
        if (l3 != null) {
            try {
                Map<Integer, float[]> l3Result = l3.getEmbeddings(l2Misses);
                for (int id : l2Misses) {
                    float[] v = l3Result.get(id);
                    if (v != null) { l3Hits.incrementAndGet(); promoteToL1IfEligible(id, v, "id:" + id); result.put(id, v); }
                    else           { misses.incrementAndGet(); }
                }
            } catch (Exception e) {
                log.warn("MultiLevelCache: L3 batch error: {}", e.toString());
                misses.addAndGet(l2Misses.size());
            }
        } else {
            misses.addAndGet(l2Misses.size());
        }

        return result;
    }

    // ── Write path ─────────────────────────────────────────────────────────────────

    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        l1.put(id, vector);                         // write-through to L1
        l2.setEmbedding(id, vector, ttlSeconds);    // write-through to L2
        if (l3 != null) {                           // best-effort write to L3
            try { l3.setEmbedding(id, vector, ttlSeconds); } catch (Exception ignored) {}
        }
    }

    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        if (vectors == null || vectors.isEmpty()) return;
        vectors.forEach((id, vec) -> { if (id != null && vec != null) l1.put(id, vec); });
        l2.setEmbeddings(vectors, ttlSeconds);
        if (l3 != null) {
            try { l3.setEmbeddings(vectors, ttlSeconds); } catch (Exception ignored) {}
        }
    }

    @Override
    public Set<Integer> scanIds(int maxKeys) {
        return l2.scanIds(maxKeys);
    }

    // ── Hot-key promotion ─────────────────────────────────────────────────────────

    // Promotes id → vec to L1 if there is capacity, or if the key is classified as hot.
    // hotKey is precomputed by the caller to avoid a second string allocation in the common path.
    // When L1 is full and the key is hot, one arbitrary entry is evicted to make room.
    private void promoteToL1IfEligible(int id, float[] vec, String hotKey) {
        if (l1.containsKey(id)) { l1.put(id, vec); return; } // refresh existing entry
        if (l1.size() < l1Capacity) { l1.put(id, vec); return; } // space available
        if (hotKeyDetector.isHot(hotKey)) {
            // Evict one arbitrary entry — not LRU, but this L1 is purpose-built for hot keys.
            Integer evict = l1.keySet().iterator().next();
            l1.remove(evict);
            l1.put(id, vec);
        }
    }

    // ── Observability ─────────────────────────────────────────────────────────────

    /** Per-tier access statistics since construction. */
    public TierStats tierStats() {
        return new TierStats(l1Hits.get(), l2Hits.get(), l3Hits.get(), misses.get());
    }

    /** Number of entries currently in the L1 JVM cache. */
    public int l1Size() { return l1.size(); }

    /**
     * Snapshot of per-tier hit counts and derived hit rates.
     *
     * @param l1Hits  hits served from JVM heap (fastest tier)
     * @param l2Hits  hits served from Redis
     * @param l3Hits  hits served from the fallback store
     * @param misses  full cache misses (all tiers returned null)
     */
    public record TierStats(long l1Hits, long l2Hits, long l3Hits, long misses) {
        public long totalRequests() { return l1Hits + l2Hits + l3Hits + misses; }

        /** Fraction of all requests served by L1 (ideal: >90% after warm-up). */
        public double l1HitRate() {
            long t = totalRequests();
            return t == 0 ? 0.0 : (double) l1Hits / t;
        }

        /** Fraction served by L2 given an L1 miss. */
        public double l2HitRate() {
            long l1Miss = l2Hits + l3Hits + misses;
            return l1Miss == 0 ? 0.0 : (double) l2Hits / l1Miss;
        }

        @Override
        public String toString() {
            return String.format(
                "TierStats{l1=%d(%.1f%%), l2=%d, l3=%d, misses=%d, total=%d}",
                l1Hits, l1HitRate() * 100, l2Hits, l3Hits, misses, totalRequests());
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────────────

    /** Fluent builder for ergonomic construction. */
    public static final class Builder {
        private final EmbeddingStore l2;
        private EmbeddingStore l3;
        private int l1Capacity = DEFAULT_L1_CAPACITY;
        private HotKeyDetector detector = new HotKeyDetector();

        public Builder(EmbeddingStore l2) { this.l2 = l2; }

        public Builder l3(EmbeddingStore l3)            { this.l3 = l3; return this; }
        public Builder l1Capacity(int cap)              { this.l1Capacity = cap; return this; }
        public Builder hotKeyDetector(HotKeyDetector d) { this.detector = d; return this; }

        public MultiLevelEmbeddingCache build() {
            return new MultiLevelEmbeddingCache(l2, l3, l1Capacity, detector);
        }
    }
}
