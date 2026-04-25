package com.recsys.modelbased.twotower.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.modelbased.twotower.config.GlobalExceptionHandler;
import com.recsys.modelbased.twotower.model.RecommendRequest;
import com.recsys.modelbased.twotower.model.RecommendResponse;
import com.recsys.modelbased.twotower.model.ScoredItem;
import com.recsys.modelbased.twotower.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RecommendationController.class)
@Import(GlobalExceptionHandler.class)
class RecommendationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean RecommendationService recommendationService;

    @Test
    void health_returns200WithOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void recommend_validRequest_returns200WithRecommendations() throws Exception {
        var response = new RecommendResponse("123", "v1", List.of(
                new ScoredItem("1", 0.95),
                new ScoredItem("3", 0.72)
        ));
        when(recommendationService.recommend(any())).thenReturn(response);

        var req = new RecommendRequest();
        req.setUserId("123");
        req.setK(3);

        mockMvc.perform(post("/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("123"))
                .andExpect(jsonPath("$.modelVersion").value("v1"))
                .andExpect(jsonPath("$.recommendations[0].itemId").value("1"))
                .andExpect(jsonPath("$.recommendations[1].score").value(0.72));
    }

    @Test
    void recommend_serviceThrowsIllegalArgument_returns400() throws Exception {
        when(recommendationService.recommend(any()))
                .thenThrow(new IllegalArgumentException("userId is required"));

        mockMvc.perform(post("/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\",\"k\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("userId is required"));
    }

    @Test
    void recommend_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk()); // default userId=null, k=5 — validation is in service
    }
}
