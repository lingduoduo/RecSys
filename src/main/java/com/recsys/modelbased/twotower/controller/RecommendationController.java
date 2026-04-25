package com.recsys.modelbased.twotower.controller;

import com.recsys.modelbased.twotower.model.RecommendRequest;
import com.recsys.modelbased.twotower.model.RecommendResponse;
import com.recsys.modelbased.twotower.service.RecommendationService;
import org.springframework.web.bind.annotation.*;

@RestController
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @PostMapping("/recommend")
    public RecommendResponse recommend(@RequestBody RecommendRequest request) {
        return recommendationService.recommend(request);
    }
}
