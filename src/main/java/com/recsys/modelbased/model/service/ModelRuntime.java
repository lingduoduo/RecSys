package com.recsys.modelbased.model.service;

record ModelRuntime(
        String variant,
        ModelArtifactService artifactService,
        CandidateSelectionService candidateSelectionService,
        FeatureEncoder featureEncoder,
        UserTowerInferenceService inferenceService,
        RetrievalService retrievalService,
        RankingService rankingService
) {
}
