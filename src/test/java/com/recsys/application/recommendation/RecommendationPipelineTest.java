package com.recsys.application.recommendation;
import com.recsys.application.recommendation.RecommendationOrchestrator;
import com.recsys.application.recommendation.RecommendationPipeline;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.application.recommendation.RecommendationHydrator;
import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.pagination.Page;
import com.recsys.application.ranking.CandidateRanker;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationPipelineTest {

    @Test
    void orchestratorImplementsPipelineInterface() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        CandidateRanker ranker = mock(CandidateRanker.class);
        CursorPaginationService pagination = mock(CursorPaginationService.class);

        RankedMovie movie = new RankedMovie("42", 0.9, 1, Map.of());
        when(recall.recallDetailed(any(), anyInt())).thenReturn(
                new RecallResult(List.of(mock(MovieCandidate.class)), Set.of()));
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of(movie));
        when(pagination.page(any(), any(), anyInt(), any(), any()))
                .thenReturn(new Page<>(List.of(movie), null));

        RecommendationPipeline pipeline =
                new RecommendationOrchestrator(recall, ranker, RecommendationHydrator.IDENTITY, pagination);

        RecommendationResult result = pipeline.recommend(
                new RecommendationQuery("u1", 5, Set.of(), null));

        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo("u1");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).itemId()).isEqualTo("42");
    }
}
