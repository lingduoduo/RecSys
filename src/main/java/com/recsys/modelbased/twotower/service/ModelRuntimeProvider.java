package com.recsys.modelbased.twotower.service;

import ai.onnxruntime.OrtException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelRuntimeProvider {

    private static final String DEFAULT_VARIANT = "training";

    private final ModelArtifactLocator artifactLocator;
    private final Map<String, ModelRuntime> runtimes = new ConcurrentHashMap<>();

    public ModelRuntimeProvider(ModelArtifactLocator artifactLocator) {
        this.artifactLocator = artifactLocator;
    }

    public ModelRuntime getRuntime(String variant) {
        String normalizedVariant = normalizeVariant(variant);
        return runtimes.computeIfAbsent(normalizedVariant, this::buildRuntime);
    }

    private ModelRuntime buildRuntime(String variant) {
        try {
            ModelArtifactService artifactService = new ModelArtifactService(artifactLocator, variant);
            artifactService.loadArtifacts();

            UserTowerInferenceService inferenceService = new UserTowerInferenceService(artifactLocator, variant);
            inferenceService.init();

            return new ModelRuntime(
                    variant,
                    artifactService,
                    new CandidateSelectionService(artifactService),
                    new FeatureEncoder(artifactService),
                    inferenceService,
                    new RetrievalService(artifactService),
                    new RankingService(artifactService)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load model artifacts for variant '" + variant + "'", e);
        } catch (OrtException e) {
            throw new IllegalStateException("Failed to initialize ONNX session for variant '" + variant + "'", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize model runtime for variant '" + variant + "'", e);
        }
    }

    @PreDestroy
    public void close() {
        for (ModelRuntime runtime : runtimes.values()) {
            try {
                runtime.inferenceService().close();
            } catch (OrtException ignored) {
                // Best-effort cleanup during shutdown.
            }
        }
        runtimes.clear();
    }

    private String normalizeVariant(String variant) {
        if (variant == null || variant.isBlank()) {
            return DEFAULT_VARIANT;
        }
        return variant.trim();
    }
}
