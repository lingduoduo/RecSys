package com.recsys.application.retrieval.multichannel;
import com.recsys.application.retrieval.multichannel.ChannelHealthMonitor;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.reliability.FaultInjector;
import com.recsys.reliability.WorkerBulkhead;
import com.recsys.application.retrieval.RecallChannel;
import com.recsys.application.retrieval.coldstart.QuotaPolicy;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiChannelRecallServiceTest {

    @Test
    void recallMergesDuplicatesByBestScoreAndSkipsExcludedItems() {
        RecallChannel vector = channel("vector",
                new MovieCandidate("1", 0.90, "vector", Map.of()),
                new MovieCandidate("2", 0.40, "vector", Map.of()));
        RecallChannel trending = channel("trending",
                new MovieCandidate("1", 0.50, "trending", Map.of()),
                new MovieCandidate("3", 0.70, "trending", Map.of()));
        MultiChannelRecallService service = new MultiChannelRecallService(List.of(vector, trending));

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("u1", 10, Set.of("2"), null),
                10
        );

        assertEquals(List.of("1", "3"), recalled.stream().map(MovieCandidate::itemId).toList());
        assertEquals("vector", recalled.get(0).channel());
    }

    @Test
    void failingChannelIsSkipped_othersStillContribute() {
        RecallChannel broken = new RecallChannel() {
            @Override public String name() { return "broken"; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                throw new RuntimeException("Redis down");
            }
        };
        RecallChannel good = channel("good",
                new MovieCandidate("42", 0.8, "good", Map.of()));

        MultiChannelRecallService service = new MultiChannelRecallService(List.of(broken, good));
        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("u1", 10, Set.of(), null), 10);

        assertThat(recalled).hasSize(1);
        assertThat(recalled.get(0).itemId()).isEqualTo("42");
    }

    @Test
    void slowChannelTimesOut_othersStillContribute() throws Exception {
        RecallChannel slow = new RecallChannel() {
            @Override public String name() { return "slow"; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                try { Thread.sleep(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return List.of(new MovieCandidate("slow-1", 0.9, "slow", Map.of()));
            }
        };
        RecallChannel fast = channel("fast", new MovieCandidate("fast-1", 0.5, "fast", Map.of()));

        WorkerBulkhead bulkhead = new WorkerBulkhead("test-recall", 4, 16);
        ChannelHealthMonitor health = new ChannelHealthMonitor();
        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(slow, fast), health, bulkhead.asExecutorService(), 100L, FaultInjector.NOOP);

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("u1", 10, Set.of(), null), 10);

        assertThat(recalled).hasSize(1);
        assertThat(recalled.get(0).itemId()).isEqualTo("fast-1");
        bulkhead.close();
    }

    @Test
    void channelBackedOff_isSkippedWithoutCall() {
        AtomicInteger callCount = new AtomicInteger();
        RecallChannel tracked = new RecallChannel() {
            @Override public String name() { return "tracked"; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                callCount.incrementAndGet();
                throw new RuntimeException("always fails");
            }
        };
        WorkerBulkhead bulkhead = new WorkerBulkhead("test-health", 4, 16);
        // Very long backoff (60s) so after threshold the channel stays down for the test duration
        ChannelHealthMonitor health = new ChannelHealthMonitor(3, 60_000L, 60_000L);
        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(tracked), health, bulkhead.asExecutorService(), 200L, FaultInjector.NOOP);

        RecommendationQuery query = new RecommendationQuery("u1", 10, Set.of(), null);
        // 3 calls to trigger backoff (failure threshold = 3)
        service.recall(query, 10);
        service.recall(query, 10);
        service.recall(query, 10);
        int callsBeforeBackoff = callCount.get();

        // Channel should now be in backoff — further call skips it
        service.recall(query, 10);
        assertThat(callCount.get()).isEqualTo(callsBeforeBackoff);
        bulkhead.close();
    }

    @Test
    void coldUser_zeroEmbeddingQuota_coldStartFillsQuota() {
        EmbeddingStore userEmb = mock(EmbeddingStore.class);
        when(userEmb.getEmbedding(1)).thenReturn(null); // no embedding → cold user

        RecallChannel embChannel   = channel("embedding",
                new MovieCandidate("10", 0.9, "embedding",    Map.of()),
                new MovieCandidate("11", 0.8, "embedding",    Map.of()));
        RecallChannel coldCh       = channel("cold_start",
                new MovieCandidate("20", 0.6, "cold_start",   Map.of()),
                new MovieCandidate("21", 0.5, "cold_start",   Map.of()),
                new MovieCandidate("22", 0.4, "cold_start",   Map.of()));
        RecallChannel trendingCh   = channel("trending",
                new MovieCandidate("30", 0.3, "trending",     Map.of()));
        RecallChannel genreCh      = channel("genre_history",
                new MovieCandidate("40", 0.2, "genre_history", Map.of()));
        RecallChannel popCh        = channel("popularity",
                new MovieCandidate("50", 0.1, "popularity",  Map.of()));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(embChannel, coldCh, trendingCh, genreCh, popCh),
                new ChannelHealthMonitor(),
                ForkJoinPool.commonPool(),
                200L,
                FaultInjector.NOOP,
                userEmb);

        // cold quota limit=10: cold_start=5, trending=2, popularity=2, genre_history=1, embedding=0
        // Quota fill: cold_start→"20","21","22" (3 of 5); trending→"30" (1 of 2); genre→"40"; pop→"50"
        // Gap fill: "10","11" from embedding (0 quota but unselected)
        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 10, Set.of(), null), 10);

        assertThat(recalled).extracting(MovieCandidate::itemId)
                .containsExactlyInAnyOrder("10", "11", "20", "21", "22", "30", "40", "50");
        assertThat(recalled).filteredOn(c -> "cold_start".equals(c.channel())).hasSize(3);
        assertThat(recalled).filteredOn(c -> "embedding".equals(c.channel())).hasSize(2);

        // Verify embedding items came via gap fill: at limit=6 (quota-fill only), embedding contributes 0
        List<MovieCandidate> quotaFillOnly = service.recall(
                new RecommendationQuery("1", 6, Set.of(), null), 6);
        assertThat(quotaFillOnly).filteredOn(c -> "embedding".equals(c.channel())).isEmpty();
    }

    @Test
    void warmUser_embeddingChannelGets60PercentQuota() {
        EmbeddingStore userEmb = mock(EmbeddingStore.class);
        when(userEmb.getEmbedding(1)).thenReturn(new float[]{0.1f}); // has embedding → warm

        RecallChannel embChannel = channel("embedding",
                new MovieCandidate("10", 0.9, "embedding", Map.of()),
                new MovieCandidate("11", 0.8, "embedding", Map.of()),
                new MovieCandidate("12", 0.7, "embedding", Map.of()),
                new MovieCandidate("13", 0.6, "embedding", Map.of()),
                new MovieCandidate("14", 0.5, "embedding", Map.of()),
                new MovieCandidate("15", 0.4, "embedding", Map.of()));
        RecallChannel trendingCh = channel("trending",
                new MovieCandidate("30", 0.3, "trending", Map.of()),
                new MovieCandidate("31", 0.2, "trending", Map.of()));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(embChannel, trendingCh),
                new ChannelHealthMonitor(),
                ForkJoinPool.commonPool(),
                200L,
                FaultInjector.NOOP,
                userEmb);

        // warm quota limit=10: embedding=6, trending=2, genre_history=2, popularity=0
        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 10, Set.of(), null), 10);

        assertThat(recalled).filteredOn(c -> "embedding".equals(c.channel())).hasSize(6);
        assertThat(recalled).filteredOn(c -> "trending".equals(c.channel())).hasSize(2);
    }

    @Test
    void nullUserEmbeddingStore_fallsBackToLegacyMaxScoreMerge() {
        // 1-arg constructor passes null userEmbeddingStore → old behavior preserved
        RecallChannel ch1 = channel("ch1",
                new MovieCandidate("A", 0.9, "ch1", Map.of()));
        RecallChannel ch2 = channel("ch2",
                new MovieCandidate("A", 0.5, "ch2", Map.of()),
                new MovieCandidate("B", 0.7, "ch2", Map.of()));

        MultiChannelRecallService service = new MultiChannelRecallService(List.of(ch1, ch2));

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 10, Set.of(), null), 10);

        // Legacy merge: "A" keeps ch1 score 0.9 (higher), "B" from ch2 0.7
        assertThat(recalled).extracting(MovieCandidate::itemId).containsExactly("A", "B");
        assertThat(recalled.get(0).score()).isEqualTo(0.9);
        assertThat(recalled.get(0).channel()).isEqualTo("ch1");
    }

    @Test
    void quotaMerge_gapFillWhenChannelReturnsFewerThanQuota() {
        EmbeddingStore userEmb = mock(EmbeddingStore.class);
        when(userEmb.getEmbedding(1)).thenReturn(new float[]{0.1f}); // warm

        // embedding has 6 quota slots but only returns 2 candidates
        RecallChannel embChannel = channel("embedding",
                new MovieCandidate("10", 0.9, "embedding", Map.of()),
                new MovieCandidate("11", 0.8, "embedding", Map.of()));
        // trending has 2 quota slots, returns 3 (one spills to gap fill)
        RecallChannel trendingCh = channel("trending",
                new MovieCandidate("30", 0.7, "trending", Map.of()),
                new MovieCandidate("31", 0.6, "trending", Map.of()),
                new MovieCandidate("32", 0.5, "trending", Map.of()));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(embChannel, trendingCh),
                new ChannelHealthMonitor(),
                ForkJoinPool.commonPool(),
                200L,
                FaultInjector.NOOP,
                userEmb);

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 10, Set.of(), null), 10);

        // Quota fill: embedding→"10","11" (2 of 6); trending→"30","31" (2 of 2)
        // Gap fill: "32" from trending leftover
        assertThat(recalled).extracting(MovieCandidate::itemId)
                .containsExactlyInAnyOrder("10", "11", "30", "31", "32");
    }

    @Test
    void injectedQuotaPolicy_drivesMerge() {
        // Custom WARM policy: popularity takes everything, embedding is residual (0 slots).
        LinkedHashMap<String, Double> warm = new LinkedHashMap<>();
        warm.put("popularity", 1.0);
        LinkedHashMap<String, Double> cold = new LinkedHashMap<>();
        cold.put("popularity", 1.0);
        QuotaPolicy popularityFirst = new QuotaPolicy(warm, "embedding", cold, "embedding");

        RecallChannel embedding = channel("embedding",
                new MovieCandidate("e1", 0.95, "embedding", java.util.Map.of()),
                new MovieCandidate("e2", 0.85, "embedding", java.util.Map.of()));
        RecallChannel popularity = channel("popularity",
                new MovieCandidate("p1", 0.50, "popularity", java.util.Map.of()),
                new MovieCandidate("p2", 0.40, "popularity", java.util.Map.of()),
                new MovieCandidate("p3", 0.30, "popularity", java.util.Map.of()));

        // Warm user: stub store returns a non-null vector for any id.
        EmbeddingStore warmStore = new AlwaysWarmStore();

        MultiChannelRecallService service = new MultiChannelRecallService(
                java.util.List.of(embedding, popularity),
                new ChannelHealthMonitor(),
                java.util.concurrent.ForkJoinPool.commonPool(),
                200L,
                FaultInjector.NOOP,
                warmStore,
                popularityFirst);

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 3, java.util.Set.of(), null), 3);

        // popularity got all 3 slots; embedding (0 quota, no gap left) contributes nothing.
        assertThat(recalled).extracting(MovieCandidate::itemId).containsExactly("p1", "p2", "p3");
        assertThat(recalled).extracting(MovieCandidate::channel).containsOnly("popularity");
    }

    // Stub: any id resolves to a non-null vector, so cold-start detection treats the user as warm.
    private static final class AlwaysWarmStore implements EmbeddingStore {
        @Override public float[] getEmbedding(int id) { return new float[]{1f}; }
        @Override public java.util.Map<Integer, float[]> getEmbeddings(java.util.Collection<Integer> ids) {
            return java.util.Map.of();
        }
        @Override public void setEmbedding(int id, float[] v, long ttl) {}
        @Override public void setEmbeddings(java.util.Map<Integer, float[]> v, long ttl) {}
        @Override public java.util.Set<Integer> scanIds(int maxKeys) { return java.util.Set.of(); }
    }

    private static RecallChannel channel(String name, MovieCandidate... candidates) {
        return new RecallChannel() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                return List.of(candidates);
            }
        };
    }
}
