package com.recsys.modelbased.model.controller;

import com.recsys.modelbased.model.dto.RecommendRequest;
import com.recsys.modelbased.model.dto.RecommendResponse;
import com.recsys.modelbased.model.service.InferenceMetricsService;
import com.recsys.modelbased.model.service.RecommendationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final InferenceMetricsService metricsService;

    public RecommendationController(RecommendationService recommendationService,
                                    InferenceMetricsService metricsService) {
        this.recommendationService = recommendationService;
        this.metricsService = metricsService;
    }

    @PostMapping(
            value = "/recommend",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public RecommendResponse recommend(@Valid @RequestBody RecommendRequest request) {
        long startNs = System.nanoTime();
        try {
            RecommendResponse response = recommendationService.recommend(request);
            metricsService.recordSuccess(elapsedMs(startNs));
            return response;
        } catch (IllegalArgumentException e) {
            // service-level guard — not an inference failure
            throw e;
        } catch (RuntimeException e) {
            metricsService.recordFailure(elapsedMs(startNs));
            throw e;
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
