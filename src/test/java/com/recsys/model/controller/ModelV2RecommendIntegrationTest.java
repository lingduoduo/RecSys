package com.recsys.model.controller;

import com.recsys.domain.prediction.ScoredItem;
import com.recsys.model.request.RecommendRequest;
import com.recsys.model.response.RecommendResponse;
import com.recsys.model.service.ABTestService;
import com.recsys.model.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ModelV2RecommendIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockBean RecommendationService recommendationService;
    @MockBean ABTestService abTestService;

    @Test
    void validRequest_returns200WithRecommendationResult() throws Exception {
        when(abTestService.getAssignmentForUser("u1")).thenReturn(
                new ABTestService.Assignment("training", 0, "default", true));
        when(recommendationService.recommend(any(RecommendRequest.class), any())).thenReturn(
                new RecommendResponse("u1", "v1.0", "training",
                        List.of(new ScoredItem("42", 0.9))));

        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u1"))
                .andExpect(jsonPath("$.items[0].itemId").value("42"))
                .andExpect(jsonPath("$.trace.abTestVariant").value("training"));
    }

    @Test
    void invalidUserId_returns400() throws Exception {
        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isBadRequest());
    }
}
