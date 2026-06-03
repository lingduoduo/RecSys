package com.recsys.modelbased.service;

import ai.onnxruntime.OrtException;
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import com.recsys.modelbased.config.ABTestConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ModelRuntimeProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelRuntimeProvider.class);

    private final ModelArtifactLocator artifactLocator;
    private final ABTestConfig abTestConfig;
    private final String modelFile;
    private final String itemEmbeddingsSource;
    private final String redisItemEmbeddingPrefix;
    private final Map<String, ModelRuntime> runtimes = new ConcurrentHashMap<>();
    private Pool<Jedis> redisItemEmbeddingPool;

    public ModelRuntimeProvider(ModelArtifactLocator artifactLocator, ABTestConfig abTestConfig) {
        this(artifactLocator, abTestConfig, "dssm_model.onnx", "classpath", "i2vEmb");
    }

    @Autowired
    public ModelRuntimeProvider(ModelArtifactLocator artifactLocator,
                                ABTestConfig abTestConfig,
                                @Value("${recsys.model.file:dssm_model.onnx}") String modelFile,
                                @Value("${recsys.model.item-embeddings-source:classpath}") String itemEmbeddingsSource,
                                @Value("${recsys.model.redis.item-embedding-prefix:i2vEmb}") String redisItemEmbeddingPrefix) {
        this.artifactLocator = artifactLocator;
        this.abTestConfig = abTestConfig;
        this.modelFile = modelFile == null || modelFile.isBlank() ? "dssm_model.onnx" : modelFile.trim();
        this.itemEmbeddingsSource = itemEmbeddingsSource == null ? "classpath" : itemEmbeddingsSource.trim();
        this.redisItemEmbeddingPrefix = redisItemEmbeddingPrefix == null || redisItemEmbeddingPrefix.isBlank()
                ? "i2vEmb"
                : redisItemEmbeddingPrefix.trim();
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
        variants.add(ModelVariants.normalizeOrDefault(abTestConfig.getDefaultVariant()));
        if (abTestConfig.isEnabled()) {
            variants.add(ModelVariants.normalizeOrDefault(abTestConfig.getBucketAVariant()));
            variants.add(ModelVariants.normalizeOrDefault(abTestConfig.getBucketBVariant()));
        }
        List<CompletableFuture<Void>> futures = variants.stream()
                .map(variant -> CompletableFuture.runAsync(() -> {
                    log.info("Pre-warming model runtime for variant '{}'", variant);
                    getRuntime(variant);
                }))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    public ModelRuntime getRuntime(String variant) {
        String normalizedVariant = ModelVariants.normalizeOrDefault(variant);
        return runtimes.computeIfAbsent(normalizedVariant, this::buildRuntime);
    }

    /**
     * Returns the already-loaded runtime for {@code variant}, or {@code null} if it has not been
     * built yet. Unlike {@link #getRuntime}, this never triggers a cold build — safe to call on
     * degradation paths where adding ONNX-load latency would worsen an overload condition.
     */
    public ModelRuntime getLoadedRuntime(String variant) {
        return runtimes.get(ModelVariants.normalizeOrDefault(variant));
    }

    public String getModelVersion(String variant) {
        return getRuntime(variant).modelVersion();
    }

    public Set<LoadedVariant> loadedVariants() {
        return runtimes.values().stream()
                .map(runtime -> new LoadedVariant(
                        runtime.variant(),
                        runtime.modelVersion(),
                        runtime.isReady()))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Returns true when every pre-warmed runtime has a live ONNX session. */
    public boolean areVariantsReady() {
        if (runtimes.isEmpty()) return false;
        return runtimes.values().stream().allMatch(r -> r.inferenceService().isReady());
    }

    private ModelRuntime buildRuntime(String variant) {
        try {
            ModelArtifactService artifactService = new ModelArtifactService(
                    artifactLocator,
                    variant,
                    redisItemEmbeddingStoreIfEnabled());
            artifactService.loadArtifacts();

            UserTowerInferenceService inferenceService = new UserTowerInferenceService(artifactLocator, variant, modelFile);
            inferenceService.init();

            return new ModelRuntime(
                    variant,
                    artifactService,
                    new CandidateSelectionService(artifactService),
                    new FeatureEncoder(artifactService),
                    inferenceService
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
        if (redisItemEmbeddingPool != null) {
            redisItemEmbeddingPool.close();
            redisItemEmbeddingPool = null;
        }
    }

    private synchronized RedisEmbeddingStore redisItemEmbeddingStoreIfEnabled() {
        if (!"redis".equalsIgnoreCase(itemEmbeddingsSource)) {
            return null;
        }
        if (redisItemEmbeddingPool == null) {
            redisItemEmbeddingPool = RedisConnectionFactory.fromEnv();
        }
        return new RedisEmbeddingStore(redisItemEmbeddingPool, redisItemEmbeddingPrefix);
    }

    public record LoadedVariant(String variant, String modelVersion, boolean ready) {
    }
}
