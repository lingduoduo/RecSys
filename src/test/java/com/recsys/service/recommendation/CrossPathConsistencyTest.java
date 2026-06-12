package com.recsys.service.recommendation;

import com.recsys.domain.Movie;
import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.domain.User;
import com.recsys.model.dto.ScoredItem;
import com.recsys.model.response.RecommendResponse;
import com.recsys.model.service.ABTestService;
import com.recsys.model.service.OnnxInferencePipeline;
import com.recsys.online.serving.OnlineBlendingPipeline;
import com.recsys.online.serving.OnlineRecommendationRequest;
import com.recsys.online.serving.OnlineRecommendationResult;
import com.recsys.online.serving.OnlineRecommendationService;
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

class CrossPathConsistencyTest {

    @Test
    void allThreePipelines_returnNonEmptyResultForSameUserId() {
        RecommendationQuery query = new RecommendationQuery("1", 5, Set.of(), null);

        // Path 1 — embedding recall via orchestrator
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        CandidateRanker ranker = mock(CandidateRanker.class);
        CursorPaginationService pagination = mock(CursorPaginationService.class);
        RankedMovie rm = new RankedMovie("10", 0.9, 1, Map.of());
        when(recall.recall(any(), anyInt())).thenReturn(List.of(mock(com.recsys.domain.MovieCandidate.class)));
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of(rm));
        when(pagination.page(any(), any(), anyInt())).thenReturn(new Page<>(List.of(rm), null));
        RecommendationPipeline path1 = new RecommendationOrchestrator(
                recall, ranker, RecommendationHydrator.IDENTITY, pagination);
        RecommendationResult r1 = path1.recommend(query);

        // Path 2 — ONNX pipeline via mocked service
        com.recsys.model.service.RecommendationService onnxService =
                mock(com.recsys.model.service.RecommendationService.class);
        ABTestService abTest = mock(ABTestService.class);
        when(abTest.getAssignmentForUser(any())).thenReturn(
                new ABTestService.Assignment("training", 0, "default", true));
        when(onnxService.recommend(any(), any())).thenReturn(
                new RecommendResponse("1", "v1", "training",
                        List.of(new ScoredItem("42", 0.8))));
        RecommendationPipeline path2 = new OnnxInferencePipeline(onnxService, abTest);
        RecommendationResult r2 = path2.recommend(query);

        // Path 3 — online blending via mocked service
        OnlineRecommendationService onlineService = mock(OnlineRecommendationService.class);
        when(onlineService.recommend(any(OnlineRecommendationRequest.class))).thenReturn(
                new OnlineRecommendationResult(
                        new User(1, "Alice"), "last_hour", "online",
                        List.of(), List.of(),
                        List.of(new Movie(7, "Film", 2020, List.of()))));
        RecommendationPipeline path3 = new OnlineBlendingPipeline(onlineService);
        RecommendationResult r3 = path3.recommend(query);

        // All three must return the same userId and non-empty items
        for (RecommendationResult result : List.of(r1, r2, r3)) {
            assertThat(result.userId()).isEqualTo("1");
            assertThat(result.items()).isNotEmpty();
            assertThat(result.items().get(0).rank()).isEqualTo(1);
        }
    }
}
