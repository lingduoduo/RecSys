package com.recsys.application.model;
import com.recsys.application.retrieval.UserTowerInferenceService;
import com.recsys.application.feature.FeatureEncoder;
import com.recsys.application.ranking.RankingStage;
import com.recsys.application.retrieval.ModelRetrievalStage;

public record ModelRuntime(
        String variant,
        ModelArtifactService artifactService,
        ModelRetrievalStage retrievalStage,
        RankingStage rankingStage,
        FeatureEncoder featureEncoder,
        UserTowerInferenceService inferenceService
) {
    public String modelVersion() {
        return artifactService.getModelVersion();
    }

    public boolean isReady() {
        return inferenceService.isReady();
    }
}
