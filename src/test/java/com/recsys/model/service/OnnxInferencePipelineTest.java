package com.recsys.model.service;

import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.domain.prediction.ScoredItem;
import com.recsys.model.request.RecommendRequest;
import com.recsys.model.response.RecommendResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OnnxInferencePipelineTest {

    private final RecommendationService service = mock(RecommendationService.class);
    private final ABTestService abTest = mock(ABTestService.class);
    private final OnnxInferencePipeline pipeline = new OnnxInferencePipeline(service, abTest);

    @Test
    void convertsQueryToRequestAndMapsResponse() {
        // ABTestService.Assignment record: (String variant, int bucket, String layerName, boolean inExperiment)
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("training", 0, "default", true);
        when(abTest.getAssignmentForUser("u1")).thenReturn(assignment);
        when(service.recommend(any(RecommendRequest.class), any())).thenReturn(
                new RecommendResponse("u1", "v1.0", "training",
                        List.of(new ScoredItem("42", 0.95), new ScoredItem("7", 0.80))));

        RecommendationResult result = pipeline.recommend(
                new RecommendationQuery("u1", 5, Set.of(), null));

        assertThat(result.userId()).isEqualTo("u1");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).itemId()).isEqualTo("42");
        assertThat(result.items().get(0).score()).isEqualTo(0.95);
        assertThat(result.items().get(0).rank()).isEqualTo(1);
        assertThat(result.items().get(1).rank()).isEqualTo(2);
        assertThat(result.nextCursor()).isNull();
        assertThat(result.trace()).containsEntry("abTestVariant", "training");
        assertThat(result.trace()).containsEntry("modelVersion", "v1.0");
    }

    @Test
    void forwardsExcludedItemIds() {
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("training", 0, "default", true);
        when(abTest.getAssignmentForUser("u2")).thenReturn(assignment);
        when(service.recommend(any(RecommendRequest.class), any())).thenReturn(
                new RecommendResponse("u2", "v1.0", "training", List.of()));

        pipeline.recommend(new RecommendationQuery("u2", 10, Set.of("1", "2"), null));

        verify(service).recommend(argThat(req ->
                req.getExcludeItemIds() != null &&
                req.getExcludeItemIds().contains("1") &&
                req.getExcludeItemIds().contains("2")), any());
    }
}
