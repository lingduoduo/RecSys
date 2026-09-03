package com.recsys.application.model;

import com.recsys.application.model.ModelArtifactManifest.ModelContract;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public record ModelArtifactSnapshot(
        byte[] featureConfig,
        byte[] model,
        String modelFile,
        String modelVersion,
        ModelContract contract,
        Map<String, byte[]> companions
) {

    public ModelArtifactSnapshot {
        featureConfig = Objects.requireNonNull(featureConfig, "featureConfig").clone();
        model = Objects.requireNonNull(model, "model").clone();
        modelFile = Objects.requireNonNull(modelFile, "modelFile");
        modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
        contract = Objects.requireNonNull(contract, "contract");
        companions = copyArtifacts(Objects.requireNonNull(companions, "companions"));
    }

    public ModelArtifactSnapshot(
            byte[] featureConfig,
            byte[] model,
            String modelFile,
            String modelVersion,
            ModelContract contract
    ) {
        this(featureConfig, model, modelFile, modelVersion, contract, Map.of());
    }

    @Override
    public byte[] featureConfig() {
        return featureConfig.clone();
    }

    @Override
    public byte[] model() {
        return model.clone();
    }

    @Override
    public Map<String, byte[]> companions() {
        return copyArtifacts(companions);
    }

    public Optional<byte[]> companion(String fileName) {
        byte[] bytes = companions.get(fileName);
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }

    private static Map<String, byte[]> copyArtifacts(Map<String, byte[]> source) {
        Map<String, byte[]> copy = new HashMap<>(source.size());
        source.forEach((name, bytes) -> copy.put(
                Objects.requireNonNull(name, "companion name"),
                Objects.requireNonNull(bytes, "companion bytes").clone()));
        return Map.copyOf(copy);
    }
}
