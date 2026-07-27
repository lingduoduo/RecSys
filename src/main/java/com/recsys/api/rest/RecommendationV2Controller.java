package com.recsys.api.rest;

import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.application.recommendation.SequentialRecommendationPipeline;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// "v2" is the PIPELINE name (recall -> rank -> hydrate -> paginate), not API version 2.
// API versions live only at the gateway edge as /api/v{n} — see docs/api-compatibility-policy.md.
@RestController
public class RecommendationV2Controller {

    private final RecommendationPipeline onnxPipeline;
    private final RecommendationPipeline sequentialPipeline;

    public RecommendationV2Controller(
            @Qualifier("onnxRecommendationPipeline") RecommendationPipeline onnxPipeline) {
        this.onnxPipeline = onnxPipeline;
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
