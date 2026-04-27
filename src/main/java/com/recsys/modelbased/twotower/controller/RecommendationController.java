package com.recsys.modelbased.twotower.controller;

import com.recsys.modelbased.twotower.model.RecommendRequest;
import com.recsys.modelbased.twotower.model.RecommendResponse;
import com.recsys.modelbased.twotower.service.InferenceMetricsService;
import com.recsys.modelbased.twotower.service.RecommendationService;
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
