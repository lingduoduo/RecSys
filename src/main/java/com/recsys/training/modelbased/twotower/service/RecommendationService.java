package com.recsys.training.modelbased.twotower.service;

import com.recsys.training.modelbased.twotower.model.RecommendRequest;
import com.recsys.training.modelbased.twotower.model.RecommendResponse;
import com.recsys.training.modelbased.twotower.model.ScoredItem;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class RecommendationService {

    private final FeatureEncoder featureEncoder;
    private final UserTowerInferenceService inferenceService;
    private final RetrievalService retrievalService;
    private final ModelArtifactService artifactService;

    public RecommendationService(
            FeatureEncoder featureEncoder,
            UserTowerInferenceService inferenceService,
            RetrievalService retrievalService,
            ModelArtifactService artifactService
    ) {
        this.featureEncoder = featureEncoder;
        this.inferenceService = inferenceService;
        this.retrievalService = retrievalService;
        this.artifactService = artifactService;
    }

    public RecommendResponse recommend(RecommendRequest request) {
        validate(request);

        FeatureEncoder.EncodedFeatures encoded = featureEncoder.encode(request);
        float[] userEmbedding = inferenceService.inferUserEmbedding(encoded);

        Set<String> excluded = new HashSet<>(request.getExcludeItemIds());
        List<ScoredItem> items = retrievalService.retrieve(userEmbedding, request.getK(), excluded);

        return new RecommendResponse(request.getUserId(), artifactService.getModelVersion(), items);
    }

    private void validate(RecommendRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (request.getK() <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        if (request.getK() > 100) {
            throw new IllegalArgumentException("k must be less than or equal to 100");
        }
    }
}
