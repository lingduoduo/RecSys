package com.recsys.api.serving;

import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.application.recommendation.RecommendationHydrator;
import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.pagination.Page;
import com.recsys.application.ranking.CandidateRanker;
import com.recsys.application.recommendation.RecommendationOrchestrator;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("load")
class EmbeddingRecallLoadTest {

    private static final int CONCURRENCY   = 20;
    private static final int TOTAL         = 200;
    private static final long TIMEOUT_S    = 60L;
    private static final long MAX_P95_MS   = 500L;
    private static final double MIN_SUCCESS = 0.99;

    @Test
    @Timeout(value = TIMEOUT_S + 10)
    void concurrentRequests_p95Under500ms() throws InterruptedException {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        CandidateRanker ranker = mock(CandidateRanker.class);
        CursorPaginationService pagination = mock(CursorPaginationService.class);
        when(recall.recall(any(), anyInt())).thenReturn(List.of());
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of());
        when(pagination.page(any(), any(), anyInt(), any(), any())).thenReturn(new Page<>(List.of(), null));

        RecommendationPipeline pipeline = new RecommendationOrchestrator(
                recall, ranker, RecommendationHydrator.IDENTITY, pagination);

        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TOTAL);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        for (int i = 0; i < TOTAL; i++) {
            final String uid = String.valueOf(i % 50 + 1);
            pool.submit(() -> {
                try {
                    startGate.await();
                    long t0 = System.nanoTime();
                    try {
                        pipeline.recommend(new RecommendationQuery(uid, 10, Set.of(), null));
                        latencies.add((System.nanoTime() - t0) / 1_000_000L);
                    } catch (RuntimeException e) {
                        latencies.add((System.nanoTime() - t0) / 1_000_000L);
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        boolean allDone = done.await(TIMEOUT_S, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(allDone).as("all %d requests completed", TOTAL).isTrue();

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        long p95 = sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
        double successRate = 1.0 - (double) errors.get() / sorted.size();

        System.out.printf("[LOAD] EmbeddingRecall: p95=%dms success=%.1f%%%n",
                p95, successRate * 100);

        assertThat(p95).as("P95 latency").isLessThanOrEqualTo(MAX_P95_MS);
        assertThat(successRate).as("success rate").isGreaterThanOrEqualTo(MIN_SUCCESS);
    }
}
