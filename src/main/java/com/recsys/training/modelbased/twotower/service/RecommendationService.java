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

    private static final int RECALL_MULTIPLIER = 5;
    private static final int MAX_RECALL_SIZE = 500;

    private final CandidateSelectionService candidateSelectionService;
    private final FeatureEncoder featureEncoder;
    private final UserTowerInferenceService inferenceService;
    private final RetrievalService retrievalService;
    private final RankingService rankingService;
    private final ModelArtifactService artifactService;

    public RecommendationService(
            CandidateSelectionService candidateSelectionService,
            FeatureEncoder featureEncoder,
            UserTowerInferenceService inferenceService,
            RetrievalService retrievalService,
            RankingService rankingService,
            ModelArtifactService artifactService
    ) {
        this.candidateSelectionService = candidateSelectionService;
        this.featureEncoder = featureEncoder;
        this.inferenceService = inferenceService;
        this.retrievalService = retrievalService;
        this.rankingService = rankingService;
        this.artifactService = artifactService;
    }

    public RecommendResponse recommend(RecommendRequest request) {
        validate(request);

        FeatureEncoder.EncodedFeatures encoded = featureEncoder.encode(request);
        float[] userEmbedding = inferenceService.inferUserEmbedding(encoded);

        Set<String> excluded = new HashSet<>(request.getExcludeItemIds());
        Integer numericUserId = parseUserId(request.getUserId());
        Set<String> candidates = candidateSelectionService.selectCandidates(numericUserId, excluded);
        int recallSize = Math.min(MAX_RECALL_SIZE, request.getK() * RECALL_MULTIPLIER);
        List<ScoredItem> recalled = retrievalService.recall(userEmbedding, numericUserId, candidates, recallSize);
        List<ScoredItem> items = rankingService.rank(userEmbedding, recalled, request.getK());

        return new RecommendResponse(request.getUserId(), artifactService.getModelVersion(), items);
    }

    private static Integer parseUserId(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            return Integer.parseInt(userId);
        } catch (NumberFormatException e) {
            return null;
        }
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
