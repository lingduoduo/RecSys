package com.recsys.api.rest;

import com.recsys.domain.prediction.ScoredItem;
import com.recsys.api.request.RecommendRequest;
import com.recsys.api.response.RecommendResponse;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.model.ModelRuntimeProvider;
import com.recsys.application.recommendation.RecommendationService;
import com.recsys.application.recommendation.RecommendationWindow;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.metrics.InferenceMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ModelV2RecommendIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean RecommendationService recommendationService;
    @MockBean ABTestService abTestService;
    @MockBean ModelRuntimeProvider modelRuntimeProvider;
    @Autowired InferenceMetricsService metricsService;

    @Test
    void v2RecommendTrafficIsVisibleToInferenceMetrics() throws Exception {
        // The point of the change: /health/ready computes "high failure rate" and "high inference
        // latency" from this snapshot, and was blind to canonical-path traffic.
        when(abTestService.getAssignmentForUser("u1")).thenReturn(
                new ABTestService.Assignment("training", 0, "default", true));
        when(recommendationService.recommendWindow(
                any(RecommendRequest.class), any(), anyInt())).thenReturn(
                new RecommendationWindow(
                        new RecommendResponse("u1", "v1.0", "training",
                                List.of(new ScoredItem("42", 0.9))),
                        false));

        long before = metricsService.snapshot().successCount();

        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isOk());

        assertThat(metricsService.snapshot().successCount()).isEqualTo(before + 1);
    }

    @Test
    void sequentialRouteStaysUnwrappedAndRecordsNoFailureMetric() throws Exception {
        // SequentialRecommendationPipeline is a stub that always throws 501. It is deliberately
        // NOT wrapped: wrapping it would record a failure per call and feed the high-failure-rate
        // readiness signal this change exists to restore. If someone wraps it later, this fails.
        long failuresBefore = metricsService.snapshot().failureCount();

        mockMvc.perform(post("/v2/sequential/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isNotImplemented());

        assertThat(metricsService.snapshot().failureCount()).isEqualTo(failuresBefore);
    }

    @Test
    void validRequest_returns200WithRecommendationResult() throws Exception {
        when(abTestService.getAssignmentForUser("u1")).thenReturn(
                new ABTestService.Assignment("training", 0, "default", true));
        when(recommendationService.recommendWindow(
                any(RecommendRequest.class), any(), anyInt())).thenReturn(
                new RecommendationWindow(
                        new RecommendResponse("u1", "v1.0", "training",
                                List.of(new ScoredItem("42", 0.9))),
                        false));

        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u1"))
                .andExpect(jsonPath("$.items[0].itemId").value("42"))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.trace.abTestVariant").value("training"));
    }

    @Test
    void cursorContinuationTraversesMultiplePagesThroughTheDefaultModelEndpoint() throws Exception {
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("training", 0, "default", true);
        List<ScoredItem> ranked = List.of(
                new ScoredItem("b", 0.9),
                new ScoredItem("a", 0.9),
                new ScoredItem("c", 0.7));
        when(abTestService.getAssignmentForUser("u1")).thenReturn(assignment);
        when(recommendationService.recommendWindow(
                any(RecommendRequest.class), same(assignment), anyInt()))
                .thenAnswer(invocation -> {
                    int candidateBudget = invocation.getArgument(2);
                    int end = Math.min(candidateBudget, ranked.size());
                    return new RecommendationWindow(
                            new RecommendResponse(
                                    "u1", "v1.0", "training", ranked.subList(0, end)),
                            ranked.size() > candidateBudget);
                });

        byte[] firstBody = mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new RecommendationQuery("u1", 2, Set.of(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].itemId").value("a"))
                .andExpect(jsonPath("$.items[1].itemId").value("b"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsByteArray();
        JsonNode first = objectMapper.readTree(firstBody);

        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new RecommendationQuery(
                                        "u1", 2, Set.of(), first.get("nextCursor").asText()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].itemId").value("c"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void invalidCursorIsRejectedGenericallyBeforeAssignmentOrModelWork() throws Exception {
        // ProtectedRecommendationPipeline resolves the A/B assignment up front (same ordering as
        // the V1 controller, for metrics/exposure logging on both success and failure), so
        // abTestService IS now hit even when the delegate goes on to reject the cursor.
        // recommendationService must still see nothing: cursor validation fails inside the
        // delegate before any candidate lookup.
        when(abTestService.getAssignmentForUser("u1")).thenReturn(
                new ABTestService.Assignment("training", 0, "default", true));

        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new RecommendationQuery(
                                        "u1", 2, Set.of(), "tampered.cursor"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid recommendation cursor"))
                .andExpect(jsonPath("$.violations").isEmpty());

        verifyNoInteractions(recommendationService);
    }

    @Test
    void rejectedCursorDoesNotCountAgainstTheReadinessFailureRate() throws Exception {
        // End-to-end companion to the unit guard: a 400 must not move the signal /health/ready
        // reads. Wrapping the pipeline put client-input rejection inside the metrics try block for
        // the first time, so without the IllegalArgumentException carve-out a client looping
        // malformed cursors drives recentFailureRate toward 1.0 and reports a healthy instance
        // degraded — the exact signal this wrapper was added to make trustworthy.
        when(abTestService.getAssignmentForUser("u1")).thenReturn(
                new ABTestService.Assignment("training", 0, "default", true));

        long failuresBefore = metricsService.snapshot().failureCount();

        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new RecommendationQuery("u1", 2, Set.of(), "tampered.cursor"))))
                .andExpect(status().isBadRequest());

        assertThat(metricsService.snapshot().failureCount()).isEqualTo(failuresBefore);
    }

    @Test
    void invalidUserId_returns400() throws Exception {
        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isBadRequest());
    }
}
