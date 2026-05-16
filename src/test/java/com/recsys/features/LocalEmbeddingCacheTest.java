package com.recsys.features;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class LocalEmbeddingCacheTest {

    private TrackingStore backing;
    private LocalEmbeddingCache cache;

    @BeforeEach
    void setUp() {
        backing = new TrackingStore();
        cache   = new LocalEmbeddingCache(backing);
    }

    @Test
    void getEmbedding_missPopulatesCache() {
        backing.put(1, new float[]{1f, 0f});

        float[] first  = cache.getEmbedding(1);
        float[] second = cache.getEmbedding(1);

        assertThat(first).isEqualTo(second);
        assertThat(backing.getCount).isEqualTo(1); // second call served from heap
    }

    @Test
    void getEmbedding_absentIdReturnsNull() {
        assertThat(cache.getEmbedding(99)).isNull();
    }

    @Test
    void getEmbeddings_batchMissesAreFetchedOnce() {
        backing.put(1, new float[]{1f, 0f});
        backing.put(2, new float[]{0f, 1f});

        Map<Integer, float[]> first  = cache.getEmbeddings(Set.of(1, 2));
        Map<Integer, float[]> second = cache.getEmbeddings(Set.of(1, 2));

        assertThat(first).containsKeys(1, 2);
        assertThat(second).containsKeys(1, 2);
        assertThat(backing.mgetCount).isEqualTo(1); // second batch served entirely from heap
    }

    @Test
    void getEmbeddings_partialCacheHit_fetchesMissesOnly() {
        backing.put(1, new float[]{1f, 0f});
        backing.put(2, new float[]{0f, 1f});

        cache.getEmbedding(1); // warm key 1
        cache.getEmbeddings(Set.of(1, 2)); // key 2 is a miss → one mget

        assertThat(backing.mgetCount).isEqualTo(1);
        assertThat(backing.getCount).isEqualTo(1);
    }

    @Test
    void setEmbedding_writesThroughToBackingAndCache() {
        float[] vec = {3f, 4f};
        cache.setEmbedding(7, vec, 0);

        assertThat(backing.data).containsKey(7);
        // subsequent get must be a cache hit (no extra backing read)
        int getsBefore = backing.getCount;
        assertThat(cache.getEmbedding(7)).isEqualTo(vec);
        assertThat(backing.getCount).isEqualTo(getsBefore);
    }

    @Test
    void setEmbeddings_writesThroughToBackingAndCache() {
        Map<Integer, float[]> vecs = Map.of(10, new float[]{1f}, 11, new float[]{2f});
        cache.setEmbeddings(vecs, 0);

        assertThat(backing.data).containsKeys(10, 11);
        int getsBefore = backing.getCount;
        assertThat(cache.getEmbedding(10)).isNotNull();
        assertThat(cache.getEmbedding(11)).isNotNull();
        assertThat(backing.getCount).isEqualTo(getsBefore);
    }

    @Test
    void preload_populatesCacheFromFileSystemData() {
        Map<Integer, float[]> fs = Map.of(1, new float[]{1f, 0f}, 2, new float[]{0f, 1f});
        cache.preload(fs);

        int getsBefore = backing.getCount;
        assertThat(cache.getEmbedding(1)).isNotNull();
        assertThat(cache.getEmbedding(2)).isNotNull();
        assertThat(backing.getCount).isEqualTo(getsBefore); // fully served from heap
        assertThat(cache.cacheSize()).isEqualTo(2);
    }

    @Test
    void cacheSize_reflectsPopulatedEntries() {
        cache.preload(Map.of(1, new float[]{1f}, 2, new float[]{2f}));
        assertThat(cache.cacheSize()).isEqualTo(2);
    }

    // ── minimal in-memory EmbeddingStore stub ──────────────────────────────

    private static final class TrackingStore implements EmbeddingStore {
        final Map<Integer, float[]> data = new HashMap<>();
        int getCount  = 0;
        int mgetCount = 0;

        void put(int id, float[] vec) { data.put(id, vec); }

        @Override
        public float[] getEmbedding(int id) {
            getCount++;
            return data.get(id);
        }

        @Override
        public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
            mgetCount++;
            Map<Integer, float[]> result = new HashMap<>();
            for (int id : ids) {
                float[] v = data.get(id);
                if (v != null) result.put(id, v);
            }
            return result;
        }

        @Override
        public void setEmbedding(int id, float[] vector, long ttlSeconds) {
            data.put(id, vector);
        }

        @Override
        public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
            data.putAll(vectors);
        }

        @Override
        public Set<Integer> scanIds(int maxKeys) { return data.keySet(); }
    }
}
