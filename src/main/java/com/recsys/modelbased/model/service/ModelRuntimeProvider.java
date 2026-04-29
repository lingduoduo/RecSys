package com.recsys.modelbased.model.service;

import ai.onnxruntime.OrtException;
import com.recsys.modelbased.model.config.ABTestConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelRuntimeProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelRuntimeProvider.class);
    private static final String DEFAULT_VARIANT = "training";

    private final ModelArtifactLocator artifactLocator;
    private final ABTestConfig abTestConfig;
    private final Map<String, ModelRuntime> runtimes = new ConcurrentHashMap<>();

    public ModelRuntimeProvider(ModelArtifactLocator artifactLocator, ABTestConfig abTestConfig) {
        this.artifactLocator = artifactLocator;
        this.abTestConfig = abTestConfig;
    }

    /**
     * Pre-warms all variants referenced by the current A/B test config so the first
     * request to each variant does not pay cold-start latency. When A/B testing is
     * disabled only the default variant is loaded; all three buckets are loaded when
     * it is enabled.
     */
    @PostConstruct
    public void warmUp() {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(normalizeVariant(abTestConfig.getDefaultVariant()));
        if (abTestConfig.isEnabled()) {
            variants.add(normalizeVariant(abTestConfig.getBucketAVariant()));
            variants.add(normalizeVariant(abTestConfig.getBucketBVariant()));
        }
        for (String variant : variants) {
            log.info("Pre-warming model runtime for variant '{}'", variant);
            getRuntime(variant);
        }
    }

    public ModelRuntime getRuntime(String variant) {
        String normalizedVariant = normalizeVariant(variant);
        return runtimes.computeIfAbsent(normalizedVariant, this::buildRuntime);
    }

    /** Returns true when every pre-warmed runtime has a live ONNX session. */
    public boolean areVariantsReady() {
        if (runtimes.isEmpty()) return false;
        return runtimes.values().stream().allMatch(r -> r.inferenceService().isReady());
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
