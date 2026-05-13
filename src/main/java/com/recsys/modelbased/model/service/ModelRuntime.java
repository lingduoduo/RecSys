package com.recsys.modelbased.model.service;

record ModelRuntime(
        String variant,
        ModelArtifactService artifactService,
        CandidateSelectionService candidateSelectionService,
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
