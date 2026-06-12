package com.recsys.service.recommendation;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.service.hydrator.RecommendationHydrator;
import com.recsys.service.pagination.CursorPaginationService;
import com.recsys.service.pagination.Page;
import com.recsys.service.ranking.CandidateRanker;
import com.recsys.service.retrieval.MultiChannelRecallService;
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
        when(recall.recall(any(), anyInt())).thenReturn(List.of(mock(MovieCandidate.class)));
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of(movie));
        when(pagination.page(any(), any(), anyInt()))
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
