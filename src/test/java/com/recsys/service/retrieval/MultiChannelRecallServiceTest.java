package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.online.ops.FaultInjector;
import com.recsys.online.ops.WorkerBulkhead;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
