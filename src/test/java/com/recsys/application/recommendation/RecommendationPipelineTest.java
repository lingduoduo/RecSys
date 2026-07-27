package com.recsys.application.recommendation;
import com.recsys.application.recommendation.RecommendationOrchestrator;
import com.recsys.application.recommendation.RecommendationPipeline;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationPipelineTest {
    private static final int MAX_CANDIDATES = 500;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void orchestratorImplementsPipelineInterface() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        CandidateRanker ranker = mock(CandidateRanker.class);

        RankedMovie movie = new RankedMovie("42", 0.9, 1, Map.of());
        when(recall.recallDetailed(any(), anyInt())).thenReturn(
                new RecallResult(List.of(mock(MovieCandidate.class)), Set.of()));
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of(movie));

        RecommendationPipeline pipeline =
                new RecommendationOrchestrator(
                        recall, ranker, RecommendationHydrator.IDENTITY,
                        pagination(), MAX_CANDIDATES);

        RecommendationResult result = pipeline.recommend(
                new RecommendationQuery("u1", 5, Set.of(), null));

        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo("u1");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).itemId()).isEqualTo("42");
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
