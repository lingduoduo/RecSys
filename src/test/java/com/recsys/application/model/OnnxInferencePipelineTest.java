package com.recsys.application.model;
import com.recsys.application.model.OnnxInferencePipeline;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.pagination.RecommendationCursorCodec;
import com.recsys.application.pagination.RecommendationPaginationConfig;
import com.recsys.application.pagination.RecommendationPaginationCoordinator;
import com.recsys.application.pagination.RecommendationPaginationMetrics;
import com.recsys.application.recommendation.RecommendationService;
import com.recsys.application.recommendation.RecommendationWindow;

import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.domain.prediction.ScoredItem;
import com.recsys.api.request.RecommendRequest;
import com.recsys.api.response.RecommendResponse;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OnnxInferencePipelineTest {

    private static final int MAX_CANDIDATES = 500;
    private final RecommendationService service = mock(RecommendationService.class);
    private final ABTestService abTest = mock(ABTestService.class);
    private final RecommendationPaginationConfig paginationConfig =
            new RecommendationPaginationConfig(
                    "a".repeat(32), null, Duration.ofMinutes(15), false, MAX_CANDIDATES);
    private final RecommendationPaginationCoordinator pagination =
            new RecommendationPaginationCoordinator(
                    new RecommendationCursorCodec(
                            paginationConfig,
                            Clock.fixed(
                                    Instant.parse("2026-07-27T12:00:00Z"),
                                    ZoneOffset.UTC)),
                    new CursorPaginationService(),
                    new RecommendationPaginationMetrics(new SimpleMeterRegistry()));
    private final OnnxInferencePipeline pipeline =
            new OnnxInferencePipeline(service, abTest, pagination, MAX_CANDIDATES);

    @Test
    void convertsQueryToRequestAndMapsResponse() {
        // ABTestService.Assignment record: (String variant, int bucket, String layerName, boolean inExperiment)
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("training", 0, "default", true);
        when(abTest.getAssignmentForUser("u1")).thenReturn(assignment);
        when(service.recommendWindow(
                any(RecommendRequest.class), any(), eq(MAX_CANDIDATES))).thenReturn(
                new RecommendationWindow(
                        new RecommendResponse("u1", "v1.0", "training",
                                List.of(
                                        new ScoredItem("42", 0.95),
                                        new ScoredItem("7", 0.80))),
                        false));

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
    void tracesTheServedVariantNotTheAssignment() {
        // Assigned to the treatment, but the service fell back to control: the trace must name
        // the model that actually ran, or metrics and exposure events attribute control's
        // results to the treatment.
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("test", 3, "default", true);
        when(abTest.getAssignmentForUser("u9")).thenReturn(assignment);
        when(service.recommendWindow(any(RecommendRequest.class), same(assignment), eq(MAX_CANDIDATES)))
                .thenReturn(new RecommendationWindow(
                        new RecommendResponse("u9", "v1.0", "training", List.of()), false));

        RecommendationResult result = pipeline.recommend(new RecommendationQuery("u9", 5, Set.of(), null));

        assertThat(result.trace()).containsEntry("abTestVariant", "training");
    }

    @Test
    void blankServedVariantFallsBackToTheAssignmentForCompatibility() {
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("test", 3, "default", true);
        when(abTest.getAssignmentForUser("u10")).thenReturn(assignment);
        when(service.recommendWindow(any(RecommendRequest.class), same(assignment), eq(MAX_CANDIDATES)))
                .thenReturn(new RecommendationWindow(
                        new RecommendResponse("u10", "v1.0", null, List.of()), false));

        RecommendationResult result = pipeline.recommend(new RecommendationQuery("u10", 5, Set.of(), null));

        assertThat(result.trace()).containsEntry("abTestVariant", "test");
    }

    @Test
    void forwardsExcludedItemIds() {
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("training", 0, "default", true);
        when(abTest.getAssignmentForUser("u2")).thenReturn(assignment);
        when(service.recommendWindow(
                any(RecommendRequest.class), any(), eq(MAX_CANDIDATES))).thenReturn(
                new RecommendationWindow(
                        new RecommendResponse("u2", "v1.0", "training", List.of()),
                        false));

        pipeline.recommend(new RecommendationQuery("u2", 10, Set.of("1", "2"), null));

        verify(service).recommendWindow(argThat(req ->
                        req.getExcludeItemIds() != null
                                && req.getExcludeItemIds().contains("1")
                                && req.getExcludeItemIds().contains("2")),
                same(assignment),
                eq(MAX_CANDIDATES));
    }

    @Test
    void explicitSourceTruncationDrivesBudgetExhaustionMetadata() {
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("training", 0, "default", true);
        when(abTest.getAssignmentForUser("u3")).thenReturn(assignment);
        when(service.recommendWindow(
                any(RecommendRequest.class), same(assignment), eq(MAX_CANDIDATES)))
                .thenReturn(new RecommendationWindow(
                        new RecommendResponse(
                                "u3",
                                "v1.0",
                                "training",
                                List.of(new ScoredItem("42", 0.95))),
                        true));

        RecommendationResult result =
                pipeline.recommend(new RecommendationQuery("u3", 1, Set.of(), null));

        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.trace())
                .containsEntry("paginationBudgetExhausted", "true");
    }
}
