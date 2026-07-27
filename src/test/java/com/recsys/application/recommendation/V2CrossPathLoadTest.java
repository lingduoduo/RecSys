package com.recsys.application.recommendation;
import com.recsys.application.recommendation.RecommendationOrchestrator;
import com.recsys.application.recommendation.RecommendationPipeline;

import com.recsys.domain.item.Movie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.user.User;
import com.recsys.domain.prediction.ScoredItem;
import com.recsys.api.response.RecommendResponse;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.model.OnnxInferencePipeline;
import com.recsys.application.online.OnlineBlendingPipeline;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.application.online.OnlineRecommendationService;
import com.recsys.application.recommendation.RecommendationHydrator;
import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.pagination.RecommendationCursorCodec;
import com.recsys.application.pagination.RecommendationPaginationConfig;
import com.recsys.application.pagination.RecommendationPaginationCoordinator;
import com.recsys.application.pagination.RecommendationPaginationMetrics;
import com.recsys.application.ranking.CandidateRanker;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("load")
class V2CrossPathLoadTest {

    private static final int MAX_CANDIDATES = 500;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC);
    private static final int CONCURRENCY  = 15;
    private static final int TOTAL        = 150;
    private static final long TIMEOUT_S   = 60L;
    private static final long MAX_P95_MS  = 500L;
    private static final double MIN_SUCCESS = 0.99;

    @Test
    @Timeout(value = TIMEOUT_S + 10)
    void allThreePipelines_steadyLoad_p95Under500ms() throws InterruptedException {
        // Path 2 — ONNX pipeline
        com.recsys.application.recommendation.RecommendationService onnxService =
                mock(com.recsys.application.recommendation.RecommendationService.class);
        ABTestService abTest = mock(ABTestService.class);
        when(abTest.getAssignmentForUser(any())).thenReturn(
                new ABTestService.Assignment("training", 0, "default", true));
        when(onnxService.recommendWindow(any(), any(), anyInt())).thenReturn(
                new RecommendationWindow(
                        new RecommendResponse("1", "v1", "training",
                                List.of(new ScoredItem("42", 0.9))),
                        false));
        RecommendationPipeline onnx =
                new OnnxInferencePipeline(onnxService, abTest, pagination(), MAX_CANDIDATES);

        // Path 3 — online blending pipeline
        OnlineRecommendationService onlineService = mock(OnlineRecommendationService.class);
        when(onlineService.recommend(any())).thenReturn(
                new OnlineRecommendationResult(
                        new User(1, "Alice"), "last_hour", "online",
                        List.of(), List.of(),
                        List.of(new Movie(7, "Film", 2020, List.of()))));
        RecommendationPipeline online =
                new OnlineBlendingPipeline(onlineService, pagination(), MAX_CANDIDATES);

        // Path 1 — orchestrator
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        CandidateRanker ranker = mock(CandidateRanker.class);
        // The orchestrator reads recallDetailed, not recall; an unstubbed mock
        // returns null and every embedding-path request fails with an NPE.
        when(recall.recallDetailed(any(), anyInt())).thenReturn(new RecallResult(
                List.of(), java.util.Set.of(), RecallResult.DegradationOutcome.HEALTHY));
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of());
        RecommendationPipeline embedding = new RecommendationOrchestrator(
                recall, ranker, RecommendationHydrator.IDENTITY,
                pagination(), MAX_CANDIDATES);

        List<RecommendationPipeline> pipelines = List.of(embedding, onnx, online);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TOTAL);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        for (int i = 0; i < TOTAL; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    RecommendationPipeline p = pipelines.get(idx % pipelines.size());
                    String uid = String.valueOf(idx % 10 + 1);
                    long t0 = System.nanoTime();
                    try {
                        p.recommend(new RecommendationQuery(uid, 5, Set.of(), null));
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

        System.out.printf("[LOAD] V2CrossPath: p95=%dms success=%.1f%%%n",
                p95, successRate * 100);

        assertThat(p95).as("P95 latency").isLessThanOrEqualTo(MAX_P95_MS);
        assertThat(successRate).as("success rate").isGreaterThanOrEqualTo(MIN_SUCCESS);
    }

    private static RecommendationPaginationCoordinator pagination() {
        RecommendationPaginationConfig config = new RecommendationPaginationConfig(
                "a".repeat(32), null, Duration.ofMinutes(15), false, MAX_CANDIDATES);
        return new RecommendationPaginationCoordinator(
                new RecommendationCursorCodec(config, FIXED_CLOCK),
                new CursorPaginationService(),
                new RecommendationPaginationMetrics(new SimpleMeterRegistry()));
    }
}
