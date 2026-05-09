package com.recsys.modelbased.model.service;

import com.recsys.modelbased.model.dto.RecommendRequest;
import com.recsys.modelbased.model.dto.RecommendResponse;
import com.recsys.modelbased.model.dto.ScoredItem;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class RecommendationService {

    private static final int RECALL_MULTIPLIER = 5;
    private static final int MAX_RECALL_SIZE = 500;

    private final ModelRuntimeProvider modelRuntimeProvider;
    private final ABTestService abTestService;

    public RecommendationService(
            ModelRuntimeProvider modelRuntimeProvider,
            ABTestService abTestService
    ) {
        this.modelRuntimeProvider = modelRuntimeProvider;
        this.abTestService = abTestService;
    }

    public RecommendResponse recommend(RecommendRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (request.getK() <= 0 || request.getK() > 100) {
            throw new IllegalArgumentException("k must be between 1 and 100");
        }
        ABTestService.Assignment assignment = abTestService.getAssignmentForUser(request.getUserId());
        ModelRuntime runtime = modelRuntimeProvider.getRuntime(assignment.variant());

        FeatureEncoder.EncodedFeatures encoded = runtime.featureEncoder().encode(request);
        float[] userEmbedding = runtime.inferenceService().inferUserEmbedding(encoded);

        Set<String> excluded = new HashSet<>(request.getExcludeItemIds());
        Integer numericUserId = parseUserId(request.getUserId());
        Set<String> candidates = runtime.candidateSelectionService().selectCandidates(numericUserId, excluded);
        int recallSize = Math.min(MAX_RECALL_SIZE, request.getK() * RECALL_MULTIPLIER);
        List<ScoredItem> recalled = runtime.retrievalService().recall(userEmbedding, numericUserId, candidates, recallSize);
        List<ScoredItem> items = runtime.rankingService().rank(userEmbedding, recalled, request.getK());

        return new RecommendResponse(
                request.getUserId(),
                runtime.artifactService().getModelVersion(),
                assignment.variant(),
                items
        );
    }

    private static Integer parseUserId(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            return Integer.parseInt(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
