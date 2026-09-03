package com.recsys.api.rest;

import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.application.recommendation.ProtectedRecommendationPipeline;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.application.recommendation.SequentialRecommendationPipeline;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// "v2" is the PIPELINE name (recall -> rank -> hydrate -> paginate), not API version 2.
// API versions live only at the gateway edge as /api/v{n} — see docs/system_design/09_API_Gateway.md#the-compatibility-contract.
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
        RecommendationResult result = onnxPipeline.recommend(query);
        // Same two headers V1 sets on its degraded path, so clients and dashboards see one signal.
        if (ProtectedRecommendationPipeline.SERVED_FROM_DEGRADED_CACHE.equals(
                result.trace().get(ProtectedRecommendationPipeline.TRACE_SERVED_FROM))) {
            return ResponseEntity.ok()
                    .header("X-Served-From", ProtectedRecommendationPipeline.SERVED_FROM_DEGRADED_CACHE)
                    .header("X-Recall-Degradation-Reason", "fallback")
                    .body(result);
        }
        return ResponseEntity.ok(result);
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
