package com.recsys.infrastructure.cache;

import com.recsys.infrastructure.vectordb.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    void coldMiss_concurrentReadersShareSingleBackingFetch() throws Exception {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f, 0f});
        backing.blockReads = true;
        CountDownLatch releaseRead = new CountDownLatch(1);
        backing.releaseRead = releaseRead;
        var cache = new LogicalExpiryEmbeddingCache(backing, 60L, SYNC_EXECUTOR);

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<float[]>>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return cache.getEmbedding(1);
            }));
        }

        assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(backing.readEntered.await(1, TimeUnit.SECONDS)).isTrue();
        releaseRead.countDown();

        for (java.util.concurrent.Future<float[]> future : futures) {
            assertThat(future.get(1, TimeUnit.SECONDS)).containsExactly(1f, 0f);
        }
        executor.shutdownNow();

        assertThat(backing.getCount).isEqualTo(1);
        assertThat(cache.inflightColdMisses()).isZero();
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

    @Test
    void getEmbeddings_returnsEmptyForNullOrEmptyInput() {
        var cache = new LogicalExpiryEmbeddingCache(new TrackingStore(), 60L, SYNC_EXECUTOR);

        assertThat(cache.getEmbeddings(null)).isEmpty();
        assertThat(cache.getEmbeddings(List.of())).isEmpty();
    }

    @Test
    void cache_staysBoundedUnderFarMoreDistinctIdsThanTheCap() {
        // Every id resolves, so nothing limits growth except the cap itself.
        var backing = new TrackingStore();
        for (int id = 0; id < 10_000; id++) backing.put(id, new float[]{id});
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR, 100);

        for (int id = 0; id < 10_000; id++) cache.getEmbedding(id);

        assertThat(cache.cacheSize()).isLessThanOrEqualTo(100);
    }

    @Test
    void nullSentinels_stayBoundedUnderFarMoreAbsentIdsThanTheCap() {
        // Nothing is put into the store, so every lookup records a negative-cache entry.
        var backing = new TrackingStore();
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR, 100);

        for (int id = 0; id < 10_000; id++) cache.getEmbedding(id);

        assertThat(cache.nullSentinelSize()).isLessThanOrEqualTo(100);
    }

    @Test
    void absentId_isNegativeCached_andNotRefetchedWithinSentinelTtl() {
        var backing = new TrackingStore(); // id 7 absent
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR);

        assertThat(cache.getEmbedding(7)).isNull();
        assertThat(backing.getCount).isEqualTo(1);
        assertThat(cache.hasNullSentinel(7)).isTrue();

        assertThat(cache.getEmbedding(7)).isNull(); // served from sentinel
        assertThat(backing.getCount).isEqualTo(1);  // no second backing call
    }

    @Test
    void sentinelExpiry_reQueriesBackingStore() throws Exception {
        var backing = new TrackingStore(); // id 7 absent initially
        // 1ms sentinel TTL so it expires almost instantly.
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 1L, SYNC_EXECUTOR);

        assertThat(cache.getEmbedding(7)).isNull();
        assertThat(backing.getCount).isEqualTo(1);

        Thread.sleep(5); // sentinel expires
        backing.put(7, new float[]{7f});
        assertThat(cache.getEmbedding(7)).containsExactly(7f); // re-queried after expiry
        assertThat(backing.getCount).isEqualTo(2);
    }

    @Test
    void setEmbedding_clearsNullSentinel() {
        var backing = new TrackingStore();
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR);

        assertThat(cache.getEmbedding(7)).isNull(); // sentinel recorded
        assertThat(cache.hasNullSentinel(7)).isTrue();

        cache.setEmbedding(7, new float[]{1f, 2f}, 300L);

        assertThat(cache.hasNullSentinel(7)).isFalse();
        assertThat(cache.getEmbedding(7)).containsExactly(1f, 2f);
    }

    @Test
    void backingException_doesNotRecordSentinel() {
        var backing = new ThrowingThenValueStore(new float[]{4f});
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR);

        // First call: backing throws -> propagates, NO sentinel recorded.
        try {
            cache.getEmbedding(7);
            org.junit.jupiter.api.Assertions.fail("expected exception");
        } catch (RuntimeException expected) {
            // expected
        }
        assertThat(cache.hasNullSentinel(7)).isFalse();

        // Second call: backing now returns a value (would be null if a sentinel had been set).
        assertThat(cache.getEmbedding(7)).containsExactly(4f);
    }

    @Test
    void getEmbeddings_recordsSentinelForBatchAbsentIds() {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f});
        var cache = new LogicalExpiryEmbeddingCache(backing, 60_000L, 60_000L, SYNC_EXECUTOR);

        Map<Integer, float[]> result = cache.getEmbeddings(List.of(1, 2));
        assertThat(result).containsKey(1).doesNotContainKey(2);
        assertThat(cache.hasNullSentinel(2)).isTrue();

        // Single-key read for the absent id is now served from the sentinel.
        int before = backing.getCount;
        assertThat(cache.getEmbedding(2)).isNull();
        assertThat(backing.getCount).isEqualTo(before);
    }

    @Test
    void backgroundRefreshResolvingNull_removesEntryAndRecordsSentinel() throws Exception {
        var backing = new TrackingStore();
        backing.put(1, new float[]{1f});
        // 1ms soft TTL, 60s sentinel, inline executor so refresh runs synchronously.
        var cache = new LogicalExpiryEmbeddingCache(backing, 1L, 60_000L, SYNC_EXECUTOR);

        assertThat(cache.getEmbedding(1)).containsExactly(1f); // cold miss populates
        Thread.sleep(5); // soft expiry passes
        backing.data.remove(1); // key vanished in backing (e.g. Redis TTL elapsed)

        // Past soft expiry: returns stale value once, schedules refresh (runs inline) which finds null.
        float[] stale = cache.getEmbedding(1);
        assertThat(stale).containsExactly(1f);

        // Entry removed, sentinel recorded -> subsequent read returns null without re-hitting backing.
        assertThat(cache.hasNullSentinel(1)).isTrue();
        int before = backing.getCount;
        assertThat(cache.getEmbedding(1)).isNull();
        assertThat(backing.getCount).isEqualTo(before);
    }

    @Test
    void backgroundRefreshNull_doesNotClobberConcurrentSetEmbedding() throws Exception {
        // Arrange: a backing store whose getEmbedding blocks on a latch then returns null,
        // simulating a key that vanished in the backing store mid-flight.
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        float[] freshVec = {7f, 8f};

        EmbeddingStore blockingNullStore = new EmbeddingStore() {
            @Override public float[] getEmbedding(int id) {
                refreshStarted.countDown();
                try { releaseRefresh.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return null; // backing store returns null (key vanished)
            }
            @Override public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) { return Map.of(); }
            @Override public void setEmbedding(int id, float[] v, long ttl) {}
            @Override public void setEmbeddings(Map<Integer, float[]> v, long ttl) {}
            @Override public Set<Integer> scanIds(int maxKeys) { return Set.of(); }
        };

        ExecutorService refreshThread = Executors.newSingleThreadExecutor();
        // 5ms soft TTL so the initial entry expires quickly; 100ms sentinel TTL.
        // Use real single-thread executor so background refresh runs concurrently with main thread.
        var cache = new LogicalExpiryEmbeddingCache(blockingNullStore, 5L, 100L, refreshThread);

        // Seed a positive entry directly into the cache.
        cache.setEmbedding(1, new float[]{1f}, -1L);

        Thread.sleep(10); // let soft TTL (5ms) expire so next read schedules background refresh

        // getEmbedding returns stale value and submits the refresh to refreshThread.
        float[] stale = cache.getEmbedding(1);
        assertThat(stale).isNotNull();

        // Wait for refresh task to have entered the blocking backing call.
        assertThat(refreshStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // While the refresh is blocked and will return null, write a fresh positive entry.
        // setEmbedding updates the cache map to a NEW LogicalEntry object.
        cache.setEmbedding(1, freshVec, -1L);

        // Release the blocked refresh — it finds null, tries CAS remove with the stale entry,
        // but setEmbedding already replaced the mapping so CAS fails → fresh entry survives.
        releaseRefresh.countDown();
        refreshThread.shutdown();
        assertThat(refreshThread.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        // Assert: fresh entry is still present and no null sentinel was planted.
        assertThat(cache.hasNullSentinel(1)).isFalse();
        assertThat(cache.cacheSize()).isEqualTo(1);
    }

    // Backing stub that throws on the first getEmbedding, then returns a fixed value.
    private static final class ThrowingThenValueStore implements EmbeddingStore {
        private final float[] value;
        private boolean thrown = false;
        ThrowingThenValueStore(float[] value) { this.value = value; }
        @Override public float[] getEmbedding(int id) {
            if (!thrown) { thrown = true; throw new RuntimeException("redis down"); }
            return value;
        }
        @Override public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) { return Map.of(); }
        @Override public void setEmbedding(int id, float[] v, long ttl) {}
        @Override public void setEmbeddings(Map<Integer, float[]> v, long ttl) {}
        @Override public Set<Integer> scanIds(int maxKeys) { return Set.of(); }
    }

    // ── minimal in-memory EmbeddingStore stub ──────────────────────────────

    private static final class TrackingStore implements EmbeddingStore {
        final Map<Integer, float[]> data = new HashMap<>();
        int getCount = 0;
        volatile boolean blockReads = false;
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(0);

        void put(int id, float[] vec) { data.put(id, vec); }

        @Override
        public synchronized float[] getEmbedding(int id) {
            getCount++;
            if (blockReads) {
                readEntered.countDown();
                try {
                    releaseRead.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return data.get(id);
        }

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
