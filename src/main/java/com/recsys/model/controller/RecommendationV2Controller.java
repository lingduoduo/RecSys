package com.recsys.model.controller;

import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.model.service.ABTestService;
import com.recsys.model.service.OnnxInferencePipeline;
import com.recsys.model.service.RecommendationService;
import com.recsys.service.recommendation.RecommendationPipeline;
import com.recsys.service.recommendation.SequentialRecommendationPipeline;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class RecommendationV2Controller {

    private final RecommendationPipeline onnxPipeline;
    private final RecommendationPipeline sequentialPipeline;

    public RecommendationV2Controller(RecommendationService recommendationService,
                                       ABTestService abTestService) {
        this.onnxPipeline = new OnnxInferencePipeline(recommendationService, abTestService);
        this.sequentialPipeline = new SequentialRecommendationPipeline();
    }

    @PostMapping(
            value = "/v2/recommend",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<RecommendationResult> recommend(@RequestBody RecommendationQuery query) {
        return ResponseEntity.ok(onnxPipeline.recommend(query));
    }

    @PostMapping(
            value = "/v2/sequential/recommend",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<RecommendationResult> recommendSequential(
            @RequestBody RecommendationQuery query) {
        return ResponseEntity.ok(sequentialPipeline.recommend(query));
    }
}
