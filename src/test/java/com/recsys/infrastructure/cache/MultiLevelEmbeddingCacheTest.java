package com.recsys.infrastructure.cache;

import com.recsys.infrastructure.resilience.HotKeyDetector;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MultiLevelEmbeddingCacheTest {

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static EmbeddingStore storeWith(Map<Integer, float[]> data) {
        return new EmbeddingStore() {
            @Override public float[] getEmbedding(int id) { return data.get(id); }
            @Override public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
                Map<Integer, float[]> r = new HashMap<>();
                ids.forEach(id -> { float[] v = data.get(id); if (v != null) r.put(id, v); });
                return r;
            }
            @Override public void setEmbedding(int id, float[] vec, long ttl) { data.put(id, vec); }
            @Override public void setEmbeddings(Map<Integer, float[]> vecs, long ttl) { data.putAll(vecs); }
            @Override public Set<Integer> scanIds(int max) { return data.keySet(); }
        };
    }

    private static EmbeddingStore throwingStore() {
        return new EmbeddingStore() {
            @Override public float[] getEmbedding(int id) { throw new RuntimeException("L2 down"); }
            @Override public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) { throw new RuntimeException("L2 down"); }
            @Override public void setEmbedding(int id, float[] vec, long ttl) {}
            @Override public void setEmbeddings(Map<Integer, float[]> vecs, long ttl) {}
            @Override public Set<Integer> scanIds(int max) { return Set.of(); }
        };
    }

    private static EmbeddingStore countingStore(Map<Integer, float[]> data, AtomicInteger reads) {
        return new EmbeddingStore() {
            @Override public float[] getEmbedding(int id) { reads.incrementAndGet(); return data.get(id); }
            @Override public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
                reads.addAndGet(ids.size());
                Map<Integer, float[]> r = new HashMap<>();
                ids.forEach(id -> { float[] v = data.get(id); if (v != null) r.put(id, v); });
                return r;
            }
            @Override public void setEmbedding(int id, float[] vec, long ttl) { data.put(id, vec); }
            @Override public void setEmbeddings(Map<Integer, float[]> vecs, long ttl) { data.putAll(vecs); }
            @Override public Set<Integer> scanIds(int max) { return data.keySet(); }
        };
    }

    // ── L1 hit ────────────────────────────────────────────────────────────────────

    @Test
    void getEmbedding_servedFromL1AfterFirstFetch() {
        AtomicInteger l2Reads = new AtomicInteger();
        Map<Integer, float[]> l2Data = new HashMap<>(Map.of(1, new float[]{1f, 0f}));
        EmbeddingStore l2 = countingStore(l2Data, l2Reads);

        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(l2).build();

        cache.getEmbedding(1); // L2 hit → promote to L1
        cache.getEmbedding(1); // L1 hit

        assertThat(l2Reads.get()).isEqualTo(1); // only one L2 read
        assertThat(cache.tierStats().l1Hits()).isEqualTo(1L);
        assertThat(cache.tierStats().l2Hits()).isEqualTo(1L);
    }

    @Test
    void getEmbedding_returnsNullOnFullMiss() {
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(storeWith(Map.of())).build();

        assertThat(cache.getEmbedding(999)).isNull();
        assertThat(cache.tierStats().misses()).isEqualTo(1L);
    }

    @Test
    void getEmbedding_shortCircuitsRepeatedMissesWithNullSentinel() {
        AtomicInteger l2Reads = new AtomicInteger();
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(
                countingStore(Map.of(), l2Reads)
        ).build();

        assertThat(cache.getEmbedding(404)).isNull();
        assertThat(cache.getEmbedding(404)).isNull();

        assertThat(l2Reads.get()).isEqualTo(1);
    }

    @Test
    void setEmbeddingClearsNullSentinelForLaterHits() {
        AtomicInteger l2Reads = new AtomicInteger();
        Map<Integer, float[]> l2Data = new HashMap<>();
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(
                countingStore(l2Data, l2Reads)
        ).build();

        assertThat(cache.getEmbedding(9)).isNull();
        cache.setEmbedding(9, new float[]{9f}, 300L);

        assertThat(cache.getEmbedding(9)).containsExactly(9f);
        assertThat(l2Reads.get()).isEqualTo(1);
    }

    // ── L2 fallback when L1 cold ──────────────────────────────────────────────────

    @Test
    void getEmbedding_fallsBackToL2OnL1Miss() {
        EmbeddingStore l2 = storeWith(new HashMap<>(Map.of(5, new float[]{5f})));
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(l2).build();

        float[] v = cache.getEmbedding(5);

        assertThat(v).containsExactly(5f);
        assertThat(cache.tierStats().l2Hits()).isEqualTo(1L);
    }

    // ── L3 fallback when L2 fails ─────────────────────────────────────────────────

    @Test
    void getEmbedding_fallsBackToL3WhenL2Throws() {
        EmbeddingStore l3 = storeWith(new HashMap<>(Map.of(7, new float[]{7f})));
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(throwingStore())
                .l3(l3)
                .build();

        float[] v = cache.getEmbedding(7);

        assertThat(v).containsExactly(7f);
        assertThat(cache.tierStats().l3Hits()).isEqualTo(1L);
        assertThat(cache.tierStats().l2Hits()).isEqualTo(0L);
    }

    @Test
    void getEmbedding_returnsNullWhenAllTiersFail() {
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(throwingStore())
                .l3(throwingStore())
                .build();

        assertThat(cache.getEmbedding(1)).isNull();
        assertThat(cache.tierStats().misses()).isEqualTo(1L);
    }

    // ── Batch reads ───────────────────────────────────────────────────────────────

    @Test
    void getEmbeddings_splitAcrossTiers() {
        AtomicInteger l2Reads = new AtomicInteger();
        Map<Integer, float[]> l2Data = new HashMap<>(Map.of(2, new float[]{2f}, 3, new float[]{3f}));
        EmbeddingStore l2 = countingStore(l2Data, l2Reads);
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(l2).build();

        // Warm ID 2 into L1.
        cache.getEmbedding(2);
        l2Reads.set(0); // reset

        // Batch: ID 2 is L1 hit; ID 3 is L2 miss-then-hit.
        Map<Integer, float[]> result = cache.getEmbeddings(java.util.List.of(2, 3));

        assertThat(result).containsKeys(2, 3);
        assertThat(l2Reads.get()).isEqualTo(1); // only ID 3 fetched from L2
    }

    @Test
    void getEmbeddings_deduplicatesL1MissesBeforeL2Fetch() {
        AtomicInteger l2Reads = new AtomicInteger();
        Map<Integer, float[]> l2Data = new HashMap<>(Map.of(1, new float[]{1f}, 2, new float[]{2f}));
        EmbeddingStore l2 = countingStore(l2Data, l2Reads);
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(l2).build();

        Map<Integer, float[]> result = cache.getEmbeddings(java.util.List.of(1, 1, 2, 2));

        assertThat(result).containsKeys(1, 2);
        assertThat(l2Reads.get()).isEqualTo(2);
    }

    // ── Write-through ─────────────────────────────────────────────────────────────

    @Test
    void setEmbedding_writesToBothL1AndL2() {
        Map<Integer, float[]> l2Data = new HashMap<>();
        EmbeddingStore l2 = storeWith(l2Data);
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(l2).build();

        cache.setEmbedding(10, new float[]{10f}, 300L);

        assertThat(l2Data).containsKey(10);       // written to L2
        assertThat(cache.l1Size()).isEqualTo(1);   // written to L1
    }

    // ── Hot-key promotion ─────────────────────────────────────────────────────────

    @Test
    void hotKeyPromotion_promotesBeyondCapacityForHotIds() {
        // L1 capacity = 2; detector threshold = 1 req/s (always hot once accessed)
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        Map<Integer, float[]> l2Data = new HashMap<>();
        for (int i = 0; i < 10; i++) l2Data.put(i, new float[]{(float) i});
        EmbeddingStore l2 = storeWith(l2Data);

        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(l2)
                .l1Capacity(2)
                .hotKeyDetector(detector)
                .build();

        // Fill L1 to capacity.
        cache.getEmbedding(0);
        cache.getEmbedding(1);
        assertThat(cache.l1Size()).isEqualTo(2);

        // Record many accesses for ID 5 so detector marks it hot.
        for (int i = 0; i < 200; i++) detector.record(5);

        // ID 5 should be promoted despite L1 being full.
        cache.getEmbedding(5);
        assertThat(cache.l1Size()).isEqualTo(2); // still at capacity (one evicted, one added)
        assertThat(cache.getEmbedding(5)).isNotNull(); // served from L1 now
    }

    // ── Per-tier stats ────────────────────────────────────────────────────────────

    @Test
    void tierStats_tracksHitsAtEachLevel() {
        Map<Integer, float[]> l2Data = new HashMap<>(Map.of(1, new float[]{1f}));
        Map<Integer, float[]> l3Data = new HashMap<>(Map.of(2, new float[]{2f}));

        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(storeWith(l2Data))
                .l3(storeWith(l3Data))
                .build();

        cache.getEmbedding(1); // L2 hit
        cache.getEmbedding(1); // L1 hit (promoted)
        cache.getEmbedding(2); // L2 miss → L3 hit
        cache.getEmbedding(9); // full miss

        MultiLevelEmbeddingCache.TierStats stats = cache.tierStats();
        assertThat(stats.l1Hits()).isEqualTo(1L);
        assertThat(stats.l2Hits()).isEqualTo(1L);
        assertThat(stats.l3Hits()).isEqualTo(1L);
        assertThat(stats.misses()).isEqualTo(1L);
        assertThat(stats.totalRequests()).isEqualTo(4L);
    }

    @Test
    void tierStats_l1HitRateIsZeroOnColdStart() {
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(storeWith(Map.of())).build();
        assertThat(cache.tierStats().l1HitRate()).isEqualTo(0.0);
    }

    @Test
    void tierStats_toStringContainsAllFields() {
        MultiLevelEmbeddingCache.TierStats s = new MultiLevelEmbeddingCache.TierStats(5, 3, 1, 1);
        String str = s.toString();
        assertThat(str).contains("l1=5").contains("l2=3").contains("l3=1").contains("misses=1");
    }

    // ── Bounded negative cache ────────────────────────────────────────────────────

    @Test
    void nullSentinels_stayBoundedUnderFarMoreAbsentIdsThanTheCap() {
        // An empty L2 makes every lookup a miss, which is what records a sentinel.
        MultiLevelEmbeddingCache cache = new MultiLevelEmbeddingCache.Builder(storeWith(Map.of()))
                .nullSentinelCapacity(100)
                .build();

        for (int id = 0; id < 10_000; id++) cache.getEmbedding(id);

        assertThat(cache.nullSentinelSize()).isLessThanOrEqualTo(100);
        assertThat(cache.tierStats().misses()).isEqualTo(10_000L);
    }
}
