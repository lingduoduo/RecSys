package com.recsys.modelbased.model.service;

import ai.onnxruntime.OrtException;
import com.recsys.features.RedisEmbeddingStore;
import com.recsys.modelbased.model.config.ABTestConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelRuntimeProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelRuntimeProvider.class);
    private static final String DEFAULT_VARIANT = ModelArtifactLocator.DEFAULT_VARIANT;

    private final ModelArtifactLocator artifactLocator;
    private final ABTestConfig abTestConfig;
    private final String itemEmbeddingsSource;
    private final String redisHost;
    private final int redisPort;
    private final String redisItemEmbeddingPrefix;
    private final Map<String, ModelRuntime> runtimes = new ConcurrentHashMap<>();
    private JedisPool redisItemEmbeddingPool;

    public ModelRuntimeProvider(ModelArtifactLocator artifactLocator, ABTestConfig abTestConfig) {
        this(artifactLocator, abTestConfig, "classpath", "localhost", 6379, "i2vEmb");
    }

    public ModelRuntimeProvider(ModelArtifactLocator artifactLocator,
                                ABTestConfig abTestConfig,
                                @Value("${recsys.model.item-embeddings-source:classpath}") String itemEmbeddingsSource,
                                @Value("${recsys.model.redis.host:localhost}") String redisHost,
                                @Value("${recsys.model.redis.port:6379}") int redisPort,
                                @Value("${recsys.model.redis.item-embedding-prefix:i2vEmb}") String redisItemEmbeddingPrefix) {
        this.artifactLocator = artifactLocator;
        this.abTestConfig = abTestConfig;
        this.itemEmbeddingsSource = itemEmbeddingsSource == null ? "classpath" : itemEmbeddingsSource.trim();
        this.redisHost = redisHost == null || redisHost.isBlank() ? "localhost" : redisHost.trim();
        this.redisPort = redisPort;
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
        ModelRuntime existing = runtimes.get(normalizedVariant);
        if (existing != null) return existing;
        // Build outside the map lock to avoid holding the CHM segment lock during I/O.
        // A duplicate build in a rare race is safe: runtimes are immutable once constructed.
        ModelRuntime built = buildRuntime(normalizedVariant);
        ModelRuntime winner = runtimes.putIfAbsent(normalizedVariant, built);
        return winner != null ? winner : built;
    }

    public String getModelVersion(String variant) {
        return getRuntime(variant).artifactService().getModelVersion();
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
        if (redisItemEmbeddingPool != null) {
            redisItemEmbeddingPool.close();
            redisItemEmbeddingPool = null;
        }
    }

    private RedisEmbeddingStore redisItemEmbeddingStoreIfEnabled() {
        if (!"redis".equalsIgnoreCase(itemEmbeddingsSource)) {
            return null;
        }
        if (redisItemEmbeddingPool == null) {
            redisItemEmbeddingPool = new JedisPool(redisHost, redisPort);
        }
        return new RedisEmbeddingStore(redisItemEmbeddingPool, redisItemEmbeddingPrefix);
    }

    private String normalizeVariant(String variant) {
        if (variant == null || variant.isBlank()) {
            return DEFAULT_VARIANT;
        }
        return variant.trim();
    }
}
