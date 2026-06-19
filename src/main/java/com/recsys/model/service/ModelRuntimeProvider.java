package com.recsys.model.service;

import ai.onnxruntime.OrtException;
import com.recsys.infrastructure.redis.RedisConnectionFactory;
import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import com.recsys.config.ABTestConfig;
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.online.ops.FaultInjector;
import com.recsys.online.store.TrendingStore;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.infrastructure.redis.ShardedTopKStore;
import com.recsys.online.store.OnlineFeatureStore;
import com.recsys.service.retrieval.channels.EmbeddingChannel;
import com.recsys.service.retrieval.channels.OnlineRecentHistoryChannel;
import com.recsys.service.retrieval.channels.PopularityChannel;
import com.recsys.service.retrieval.channels.TrendingChannel;
import com.recsys.service.retrieval.coldstart.ColdStartChannel;
import com.recsys.service.retrieval.coldstart.QuotaPolicy;
import com.recsys.service.retrieval.multichannel.ChannelHealthMonitor;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.service.retrieval.multichannel.RecallConfig;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class ModelRuntimeProvider implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ModelRuntimeProvider.class);

    /**
     * Connect/socket timeout cap for the recall Jedis pool. Set below the 200 ms
     * per-channel recall budget so a down or slow Redis fails fast to the in-memory
     * fallback instead of stalling recall-executor threads.
     */
    private static final int RECALL_REDIS_TIMEOUT_MS = 150;

    private final ModelArtifactLocator artifactLocator;
    private final ABTestConfig abTestConfig;
    private final String modelFile;
    private final String itemEmbeddingsSource;
    private final String redisItemEmbeddingPrefix;
    private final Map<String, ModelRuntime> runtimes = new ConcurrentHashMap<>();
    private Pool<Jedis> redisItemEmbeddingPool;
    private Pool<Jedis> recallPool;
    private CandidateGenerator candidateGenerator;
    private TrendingStore topkStore;
    private GlobalPopularityStore globalPopStore;
    private OnlineFeatureStore onlineFeatureStore;
    private ExecutorService recallExecutor;
    private ChannelHealthMonitor sharedHealthMonitor;
    private final Object recallLock = new Object();

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
    @Override
    public void afterSingletonsInstantiated() {
        warmUp();
    }

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

    /**
     * Builds the recall stores once and shares them across variants. Jedis pools and the
     * Redis-backed stores connect lazily, so this never fails when Redis is unavailable —
     * a request-time recall miss falls back to CandidateSelectionService in RecommendationService.
     */
    private void ensureRecallInfra() {
        synchronized (recallLock) {
            if (candidateGenerator != null) return;
            recallPool = RedisConnectionFactory.fromEnv(RECALL_REDIS_TIMEOUT_MS);
            DataManager dataManager = DataManager.getInstance();
            candidateGenerator = new CandidateGenerator(dataManager, new RedisEmbeddingStore(recallPool, "u2vEmb"));
            topkStore = new ShardedTopKStore(recallPool, "topk:");
            globalPopStore = new GlobalPopularityStore(recallPool);
            onlineFeatureStore = new OnlineFeatureStore(recallPool);
            recallExecutor = Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors() * 2,
                    r -> new Thread(r, "model-recall-channel"));
            sharedHealthMonitor = new ChannelHealthMonitor();
        }
    }

    private MultiChannelRecallService buildRecallService(ModelArtifactService artifactService) {
        ensureRecallInfra();
        DataManager dataManager = DataManager.getInstance();
        return MultiChannelRecallService.from(
                RecallConfig.builder()
                        .channels(java.util.List.of(
                                new EmbeddingChannel(candidateGenerator),
                                new OnlineRecentHistoryChannel(onlineFeatureStore, dataManager),
                                new TrendingChannel(topkStore, java.util.List.of("last_hour", "last_day")),
                                new PopularityChannel(dataManager, globalPopStore),
                                new ColdStartChannel(topkStore, globalPopStore)))
                        .quotaPolicy(QuotaPolicy.defaultModelRetrieval())
                        .healthMonitor(sharedHealthMonitor)
                        .executor(recallExecutor)
                        .faultInjector(FaultInjector.NOOP)
                        .userEmbeddingStore(new VocabMembershipEmbeddingStore(artifactService.getUserVocab()))
                        .build());
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

            FeatureEncoder featureEncoder = new FeatureEncoder(artifactService);
            ModelRetrievalStage retrievalStage = new ModelRetrievalStage(buildRecallService(artifactService), onlineFeatureStore);
            RankingStage rankingStage = new RankingStage(inferenceService, featureEncoder, artifactService);

            return new ModelRuntime(
                    variant,
                    artifactService,
                    retrievalStage,
                    rankingStage,
                    featureEncoder,
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
        if (recallExecutor != null) {
            recallExecutor.shutdownNow();
            recallExecutor = null;
        }
        if (recallPool != null) {
            recallPool.close();
            recallPool = null;
        }
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
