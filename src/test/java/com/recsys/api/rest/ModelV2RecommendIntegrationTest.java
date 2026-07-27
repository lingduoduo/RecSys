package com.recsys.api.rest;

import com.recsys.domain.prediction.ScoredItem;
import com.recsys.api.request.RecommendRequest;
import com.recsys.api.response.RecommendResponse;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.model.ModelRuntimeProvider;
import com.recsys.application.recommendation.RecommendationService;
import com.recsys.application.recommendation.RecommendationWindow;
import com.recsys.domain.recommendation.RecommendationQuery;
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
        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new RecommendationQuery(
                                        "u1", 2, Set.of(), "tampered.cursor"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid recommendation cursor"))
                .andExpect(jsonPath("$.violations").isEmpty());

        verifyNoInteractions(abTestService, recommendationService);
    }

    @Test
    void invalidUserId_returns400() throws Exception {
        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isBadRequest());
    }
}
