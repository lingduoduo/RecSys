package com.recsys.modelbased.model.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.modelbased.model.config.GlobalExceptionHandler;
import com.recsys.modelbased.model.dto.RecommendRequest;
import com.recsys.modelbased.model.dto.RecommendResponse;
import com.recsys.modelbased.model.dto.ScoredItem;
import com.recsys.modelbased.model.service.ABTestService;
import com.recsys.modelbased.model.service.InferenceMetricsService;
import com.recsys.modelbased.model.service.LoadShedder;
import com.recsys.modelbased.model.service.ModelRateLimiter;
import com.recsys.modelbased.model.service.RecommendationService;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RecommendationController.class)
@Import(GlobalExceptionHandler.class)
class RecommendationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean RecommendationService recommendationService;
    @MockBean InferenceMetricsService metricsService;
    @MockBean ABTestService abTestService;
    @MockBean LoadShedder loadShedder;
    @MockBean ModelRateLimiter modelRateLimiter;

    @BeforeEach
    void allowRequest() {
        when(loadShedder.tryAcquire()).thenReturn(true);
        when(abTestService.getVariantForUser(any())).thenReturn("training");
        // Default snapshot used by the controller for the X-Capacity-Weight header.
        when(loadShedder.snapshot()).thenReturn(
                new LoadShedder.Snapshot(0, 64, 0.0, 0.95, 0L, 0L, 100, false));
        // Rate limiter allows all requests by default; individual tests override this.
        when(modelRateLimiter.tryAcquire(any())).thenReturn(ModelRateLimiter.Decision.unlimited());
    }

    // --- happy path ---

    @Test
    void recommend_validRequest_returns200WithRecommendations() throws Exception {
        var response = new RecommendResponse("123", "v1", "training", List.of(
                new ScoredItem("1", 0.95),
                new ScoredItem("3", 0.72)
        ));
        when(recommendationService.recommend(any())).thenReturn(response);

        var req = new RecommendRequest();
        req.setUserId("123");
        req.setK(3);

        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("123"))
                .andExpect(jsonPath("$.modelVersion").value("v1"))
                .andExpect(jsonPath("$.recommendations[0].itemId").value("1"))
                .andExpect(jsonPath("$.recommendations[1].score").value(0.72));
    }

    // --- bean validation ---

    @Test
    void recommend_missingUserId_returns400WithViolation() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"k\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"))
                .andExpect(jsonPath("$.violations[0].field").value("userId"));
    }

    @Test
    void recommend_blankUserId_returns400WithViolation() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\",\"k\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"))
                .andExpect(jsonPath("$.violations[0].field").value("userId"));
    }

    @Test
    void recommend_kZero_returns400WithViolation() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"123\",\"k\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"))
                .andExpect(jsonPath("$.violations[0].field").value("k"));
    }

    @Test
    void recommend_kAbove100_returns400WithViolation() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"123\",\"k\":101}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"))
                .andExpect(jsonPath("$.violations[0].field").value("k"));
    }

    // --- malformed / wrong content-type ---

    @Test
    void recommend_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed request body"));
    }

    @Test
    void recommend_wrongContentType_returns415() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"userId\":\"123\",\"k\":5}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("Content-Type must be application/json"));
    }

    // --- service-level error still surfaces as 400 ---

    @Test
    void recommend_serviceThrowsIllegalArgument_returns400() throws Exception {
        when(recommendationService.recommend(any()))
                .thenThrow(new IllegalArgumentException("custom service rule violated"));

        var req = new RecommendRequest();
        req.setUserId("123");
        req.setK(5);

        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("custom service rule violated"))
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void recommend_userRateLimited_returns429WithRetryAfter() throws Exception {
        when(modelRateLimiter.tryAcquire("123"))
                .thenReturn(new ModelRateLimiter.Decision(false, 10, 0, Duration.ofSeconds(2)));

        var req = new RecommendRequest();
        req.setUserId("123");
        req.setK(5);

        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"));
    }

    @Test
    void recommend_overloaded_returns503WithRetryAfter() throws Exception {
        when(loadShedder.tryAcquire()).thenReturn(false);

        var req = new RecommendRequest();
        req.setUserId("123");
        req.setK(5);

        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("recommendation service is overloaded"));

        verify(metricsService).recordFailure(0L, "training");
    }
}
