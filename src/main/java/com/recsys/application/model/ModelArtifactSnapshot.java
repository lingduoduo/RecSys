package com.recsys.application.model;

import com.recsys.application.model.ModelArtifactManifest.ModelContract;

import java.util.Objects;

public record ModelArtifactSnapshot(
        byte[] featureConfig,
        byte[] model,
        String modelFile,
        String modelVersion,
        ModelContract contract
) {

    public ModelArtifactSnapshot {
        featureConfig = Objects.requireNonNull(featureConfig, "featureConfig").clone();
        model = Objects.requireNonNull(model, "model").clone();
        modelFile = Objects.requireNonNull(modelFile, "modelFile");
        modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
        contract = Objects.requireNonNull(contract, "contract");
    }

    @Override
    public byte[] featureConfig() {
        return featureConfig.clone();
    }

    @Override
    public byte[] model() {
        return model.clone();
    }
}
