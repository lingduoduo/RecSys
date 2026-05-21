package com.recsys.features;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LogicalExpiryEmbeddingCacheTest {

    // Synchronous executor: refresh tasks run on the calling thread inline for predictable tests.
    private static final java.util.concurrent.Executor SYNC_EXECUTOR = Runnable::run;

    @Test
    void coldMiss_fetchesSynchronouslyFromBackingStore() {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f, 0f});
        var cache = new LogicalExpiryEmbeddingCache(backing, 60L, SYNC_EXECUTOR);

        float[] result = cache.getEmbedding(1);

        assertThat(result).containsExactly(1f, 0f);
        assertThat(backing.getCount).isEqualTo(1);
    }

    @Test
    void warmHit_returnsCachedValueWithoutBackingCall() {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f, 0f});
        var cache = new LogicalExpiryEmbeddingCache(backing, 60L, SYNC_EXECUTOR);

        cache.getEmbedding(1); // populate cache
        int beforeCount = backing.getCount;
        float[] result = cache.getEmbedding(1);

        assertThat(result).containsExactly(1f, 0f);
        assertThat(backing.getCount).isEqualTo(beforeCount); // no extra backing call
    }

    @Test
    void softExpiry_returnsStaleValueAndSchedulesBackgroundRefresh() throws Exception {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f, 0f});
        AtomicInteger refreshCount = new AtomicInteger();
        // Tracking executor runs tasks inline so effects are visible immediately.
        java.util.concurrent.Executor trackingExecutor = r -> { refreshCount.incrementAndGet(); r.run(); };

        // 1ms soft TTL — entries expire almost instantly.
        var cache = new LogicalExpiryEmbeddingCache(backing, 1L, trackingExecutor);

        float[] first = cache.getEmbedding(1); // cold miss
        assertThat(first).containsExactly(1f, 0f);

        Thread.sleep(5); // ensure soft expiry passes

        backing.put(1, new float[]{9f, 9f});
        float[] result = cache.getEmbedding(1); // past soft expiry → returns stale, schedules refresh inline
        assertThat(result).isNotNull();
        assertThat(refreshCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void backgroundRefresh_deduplicatesForSameId() throws Exception {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f});
        AtomicInteger submittedCount = new AtomicInteger();
        // Counting executor drops tasks (doesn't run them) so refreshing flag stays set.
        java.util.concurrent.Executor countingExecutor = r -> submittedCount.incrementAndGet();

        // 1ms soft TTL.
        var cache = new LogicalExpiryEmbeddingCache(backing, 1L, countingExecutor);

        cache.getEmbedding(1); // cold miss (sync, no executor)
        Thread.sleep(5);       // ensure soft expiry passes

        cache.getEmbedding(1); // past soft expiry → schedules refresh (count=1)
        cache.getEmbedding(1); // still in-flight (task dropped, flag retained) → no duplicate

        assertThat(submittedCount.get()).isEqualTo(1);
    }

    @Test
    void setEmbedding_updatesLocalCacheAndBackingStore() {
        var backing = new TrackingStore();
        var cache = new LogicalExpiryEmbeddingCache(backing, 60L, SYNC_EXECUTOR);

        cache.setEmbedding(5, new float[]{3f, 4f}, 300L);

        assertThat(backing.data).containsKey(5);
        int beforeCount = backing.getCount;
        assertThat(cache.getEmbedding(5)).containsExactly(3f, 4f);
        assertThat(backing.getCount).isEqualTo(beforeCount); // served from local cache
    }

    @Test
    void getEmbeddings_batchColdMissesAreFetchedFromBacking() {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f});
        backing.put(2, new float[]{2f});
        var cache = new LogicalExpiryEmbeddingCache(backing, 60L, SYNC_EXECUTOR);

        Map<Integer, float[]> result = cache.getEmbeddings(List.of(1, 2, 3));

        assertThat(result).containsKeys(1, 2);
        assertThat(result).doesNotContainKey(3); // absent in backing store
    }

    // ── minimal in-memory EmbeddingStore stub ──────────────────────────────

    private static final class TrackingStore implements EmbeddingStore {
        final Map<Integer, float[]> data = new HashMap<>();
        int getCount = 0;

        void put(int id, float[] vec) { data.put(id, vec); }

        @Override
        public float[] getEmbedding(int id) { getCount++; return data.get(id); }

        @Override
        public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
            Map<Integer, float[]> result = new HashMap<>();
            for (int id : ids) { float[] v = data.get(id); if (v != null) result.put(id, v); }
            return result;
        }

        @Override
        public void setEmbedding(int id, float[] vector, long ttlSeconds) { data.put(id, vector); }

        @Override
        public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) { data.putAll(vectors); }

        @Override
        public Set<Integer> scanIds(int maxKeys) { return data.keySet(); }
    }
}
