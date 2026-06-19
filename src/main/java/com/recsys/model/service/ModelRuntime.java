package com.recsys.model.service;

record ModelRuntime(
        String variant,
        ModelArtifactService artifactService,
        ModelRetrievalStage retrievalStage,
        RankingStage rankingStage,
        FeatureEncoder featureEncoder,
        UserTowerInferenceService inferenceService
) {
    String modelVersion() {
        return artifactService.getModelVersion();
    }

    boolean isReady() {
        return inferenceService.isReady();
    }
}
