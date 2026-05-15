package com.recsys.modelbased.model.controller;

import com.recsys.modelbased.model.dto.ApiError;
import com.recsys.modelbased.model.dto.RecommendRequest;
import com.recsys.modelbased.model.dto.RecommendResponse;
import com.recsys.modelbased.model.service.InferenceMetricsService;
import com.recsys.modelbased.model.service.ModelRuntimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end tests for the full request chain:
 *   Client → RecommendationController → RecommendationService (inference) → InferenceMetricsService → Response
 *
 * Uses the full Spring Boot context with MockMvc so every application layer is exercised
 * without requiring a real TCP listener in constrained test environments.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecommendationEndToEndTest {

    @Autowired MockMvc mockMvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired InferenceMetricsService metricsService;
    @Autowired ModelRuntimeProvider modelRuntimeProvider;

    // ── 1. Full chain: valid request produces ranked recommendations ──────────

    @Test
    void fullChain_validRequest_returnsRankedRecommendations() throws Exception {
        var resp = mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request("123", 5))))
                .andReturn()
                .getResponse();

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
        var body = readBody(resp.getContentAsByteArray(), RecommendResponse.class);
        assertThat(body.userId()).isEqualTo("123");
        assertThat(body.modelVersion()).isNotBlank();
        assertThat(body.recommendations()).isNotEmpty();

        // scores must be in descending order
        List<Double> scores = body.recommendations().stream().map(i -> i.score()).toList();
        for (int i = 0; i + 1 < scores.size(); i++) {
            assertThat(scores.get(i))
                    .as("score[%d] >= score[%d]", i, i + 1)
                    .isGreaterThanOrEqualTo(scores.get(i + 1));
        }
    }

    // ── 2. MetricsService is updated after a successful request ───────────────

    @Test
    void fullChain_successfulRequest_incrementsSuccessCounter() throws Exception {
        var before = metricsService.snapshot();

        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request("123", 3))))
                .andReturn();

        var after = metricsService.snapshot();
        assertThat(after.totalRequests()).as("totalRequests").isEqualTo(before.totalRequests() + 1);
        assertThat(after.successCount()).as("successCount").isEqualTo(before.successCount() + 1);
        assertThat(after.failureCount()).as("failureCount").isEqualTo(before.failureCount());
    }

    // ── 3. Bean-validation rejection never reaches the metrics layer ──────────
    //      (@Valid fires before the method body, so recordSuccess/recordFailure
    //       are never called — validation errors are the caller's fault, not ours)

    @Test
    void fullChain_validationRejection_doesNotTouchMetrics() throws Exception {
        var before = metricsService.snapshot();

        // missing userId triggers @NotBlank — Spring MVC rejects before entering the method
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("k", 5))))
                .andReturn();

        var after = metricsService.snapshot();
        assertThat(after.totalRequests()).as("totalRequests unchanged").isEqualTo(before.totalRequests());
        assertThat(after.failureCount()).as("failureCount unchanged").isEqualTo(before.failureCount());
    }

    // ── 4. Invalid input → stable ApiError shape with field-level violations ──

    @Test
    void fullChain_invalidInput_returns400WithViolations() throws Exception {
        // k=0 violates @Min(1); userId missing violates @NotBlank
        var resp = mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("k", 0))))
                .andReturn()
                .getResponse();

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        var error = readBody(resp.getContentAsByteArray(), ApiError.class);
        assertThat(error.error()).isEqualTo("validation failed");
        assertThat(error.violations()).isNotEmpty();
        assertThat(error.violations().stream().map(ApiError.Violation::field))
                .contains("k");
    }

    // ── 5. Health probes reflect real model + metrics state ───────────────────

    @Test
    void healthLive_alwaysReturnsUp() throws Exception {
        var resp = mockMvc.perform(get("/health/live"))
                .andReturn()
                .getResponse();

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(readMap(resp.getContentAsByteArray())).containsEntry("status", "UP");
    }

    @Test
    void healthReady_modelLoaded_returnsUp() throws Exception {
        // model is loaded by @PostConstruct before any test runs;
        // recentRequests < minSampleSize so threshold checks are skipped — just confirms model ready
        var resp = mockMvc.perform(get("/health/ready"))
                .andReturn()
                .getResponse();

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(readMap(resp.getContentAsByteArray()))
                .containsEntry("status", "UP")
                .containsKey("inFlightRequests")
                .containsKey("maxConcurrentRequests")
                .containsKey("suggestedWeight");
    }

    @Test
    void healthMetrics_afterRequest_reflectsRecordedStats() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request("123", 5))))
                .andReturn();

        var resp = mockMvc.perform(get("/health/metrics"))
                .andReturn()
                .getResponse();

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
        var body = readMap(resp.getContentAsByteArray());
        assertThat((Number) body.get("totalRequests")).extracting(Number::longValue)
                .as("totalRequests > 0").matches(n -> n > 0);
        assertThat(body).containsKey("allTimeAvgLatencyMs").containsKey("throughputPerSecond");
    }

    @Test
    void abTestMetrics_afterRequest_exposesVariantComparison() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request("123", 5))))
                .andReturn();

        var resp = mockMvc.perform(get("/health/ab-tests"))
                .andReturn()
                .getResponse();

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
        var body = readMap(resp.getContentAsByteArray());
        assertThat(body).containsEntry("controlVariant", "training");
        assertThat(body).containsKey("variants");

        @SuppressWarnings("unchecked")
        Map<String, Object> variants = (Map<String, Object>) body.get("variants");
        assertThat(variants).containsKey("training");

        @SuppressWarnings("unchecked")
        Map<String, Object> training = (Map<String, Object>) variants.get("training");
        assertThat(training).containsEntry("modelVersion", modelRuntimeProvider.getModelVersion("training"));
        assertThat(((Number) training.get("totalRequests")).longValue()).isGreaterThan(0L);
    }

    @Test
    void versionController_preloadActivateAndRollback_managesActiveModelVariant() throws Exception {
        var preloadResp = mockMvc.perform(post("/api/v1/model/versions/preload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variant\":\"test\"}"))
                .andReturn()
                .getResponse();

        assertThat(preloadResp.getStatus()).isEqualTo(HttpStatus.OK.value());
        var preloaded = readMap(preloadResp.getContentAsByteArray());
        assertThat(preloaded).containsEntry("activeVariant", "training");

        var activateResp = mockMvc.perform(post("/api/v1/model/versions/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variant\":\"test\"}"))
                .andReturn()
                .getResponse();

        assertThat(activateResp.getStatus()).isEqualTo(HttpStatus.OK.value());
        var activated = readMap(activateResp.getContentAsByteArray());
        assertThat(activated).containsEntry("activeVariant", "test");
        assertThat(activated).containsEntry("previousActiveVariant", "training");

        var recommendResp = mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request("123", 3))))
                .andReturn()
                .getResponse();
        var recommendBody = readBody(recommendResp.getContentAsByteArray(), RecommendResponse.class);
        assertThat(recommendBody.abTestVariant()).isEqualTo("test");
        assertThat(recommendBody.modelVersion()).isEqualTo(modelRuntimeProvider.getModelVersion("test"));

        var rollbackResp = mockMvc.perform(post("/api/v1/model/versions/rollback"))
                .andReturn()
                .getResponse();

        assertThat(rollbackResp.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(readMap(rollbackResp.getContentAsByteArray()))
                .containsEntry("activeVariant", "training")
                .containsEntry("previousActiveVariant", "test");
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static RecommendRequest request(String userId, int k) {
        var req = new RecommendRequest();
        req.setUserId(userId);
        req.setK(k);
        return req;
    }

    private <T> T readBody(byte[] body, Class<T> type) throws Exception {
        return Objects.requireNonNull(objectMapper.readValue(body, type), "response body");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(byte[] body) throws Exception {
        return objectMapper.readValue(body, LinkedHashMap.class);
    }
}
