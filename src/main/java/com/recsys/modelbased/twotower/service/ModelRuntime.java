package com.recsys.modelbased.twotower.service;

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
