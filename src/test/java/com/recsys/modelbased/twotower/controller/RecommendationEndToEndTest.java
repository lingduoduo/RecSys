package com.recsys.modelbased.twotower.controller;

import com.recsys.modelbased.twotower.model.ApiError;
import com.recsys.modelbased.twotower.model.RecommendRequest;
import com.recsys.modelbased.twotower.model.RecommendResponse;
import com.recsys.modelbased.twotower.service.InferenceMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the full request chain:
 *   Client → RecommendationController → RecommendationService (inference) → InferenceMetricsService → Response
 *
 * Uses a real Spring Boot context and HTTP server so every layer is exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecommendationEndToEndTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired InferenceMetricsService metricsService;

    // ── 1. Full chain: valid request produces ranked recommendations ──────────

    @Test
    void fullChain_validRequest_returnsRankedRecommendations() {
        var resp = restTemplate.postForEntity("/api/v1/recommend", request("123", 5), RecommendResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = Objects.requireNonNull(resp.getBody(), "recommend response body");
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
    void fullChain_successfulRequest_incrementsSuccessCounter() {
        var before = metricsService.snapshot();

        restTemplate.postForEntity("/api/v1/recommend", request("123", 3), RecommendResponse.class);

        var after = metricsService.snapshot();
        assertThat(after.totalRequests()).as("totalRequests").isEqualTo(before.totalRequests() + 1);
        assertThat(after.successCount()).as("successCount").isEqualTo(before.successCount() + 1);
        assertThat(after.failureCount()).as("failureCount").isEqualTo(before.failureCount());
    }

    // ── 3. Bean-validation rejection never reaches the metrics layer ──────────
    //      (@Valid fires before the method body, so recordSuccess/recordFailure
    //       are never called — validation errors are the caller's fault, not ours)

    @Test
    void fullChain_validationRejection_doesNotTouchMetrics() {
        var before = metricsService.snapshot();

        // missing userId triggers @NotBlank — Spring MVC rejects before entering the method
        restTemplate.postForEntity("/api/v1/recommend", Map.of("k", 5), ApiError.class);

        var after = metricsService.snapshot();
        assertThat(after.totalRequests()).as("totalRequests unchanged").isEqualTo(before.totalRequests());
        assertThat(after.failureCount()).as("failureCount unchanged").isEqualTo(before.failureCount());
    }

    // ── 4. Invalid input → stable ApiError shape with field-level violations ──

    @Test
    void fullChain_invalidInput_returns400WithViolations() {
        // k=0 violates @Min(1); userId missing violates @NotBlank
        var resp = restTemplate.postForEntity(
                "/api/v1/recommend", Map.of("k", 0), ApiError.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var error = Objects.requireNonNull(resp.getBody(), "error response body");
        assertThat(error.error()).isEqualTo("validation failed");
        assertThat(error.violations()).isNotEmpty();
        assertThat(error.violations().stream().map(ApiError.Violation::field))
                .contains("k");
    }

    // ── 5. Health probes reflect real model + metrics state ───────────────────

    @Test
    void healthLive_alwaysReturnsUp() {
        var resp = restTemplate.exchange(
                "/health/live", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, String>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "UP");
    }

    @Test
    void healthReady_modelLoaded_returnsUp() {
        // model is loaded by @PostConstruct before any test runs;
        // recentRequests < minSampleSize so threshold checks are skipped — just confirms model ready
        var resp = restTemplate.exchange(
                "/health/ready", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "UP");
    }

    @Test
    void healthMetrics_afterRequest_reflectsRecordedStats() {
        restTemplate.postForEntity("/api/v1/recommend", request("123", 5), RecommendResponse.class);

        var resp = restTemplate.exchange(
                "/health/metrics", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = Objects.requireNonNull(resp.getBody(), "metrics response body");
        assertThat((Number) body.get("totalRequests")).extracting(Number::longValue)
                .as("totalRequests > 0").matches(n -> n > 0);
        assertThat(body).containsKey("allTimeAvgLatencyMs").containsKey("throughputPerSecond");
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static RecommendRequest request(String userId, int k) {
        var req = new RecommendRequest();
        req.setUserId(userId);
        req.setK(k);
        return req;
    }
}
