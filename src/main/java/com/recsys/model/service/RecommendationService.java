package com.recsys.model.service;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.featureflags.FeatureFlagService;
import com.recsys.infrastructure.featureflags.Flags;
import com.recsys.config.RecommendationCacheProperties;
import com.recsys.model.converter.RecommendationConverter;
import com.recsys.model.request.RecommendRequest;
import com.recsys.model.response.RecommendResponse;
import com.recsys.domain.prediction.ScoredItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class RecommendationService {

    private static final int RECALL_MULTIPLIER = 5;
    private static final int MIN_RECALL_SIZE = 50;
    private static final int MAX_RECALL_LIMIT = 100; // shared RecommendationQuery cap

    private final ModelRuntimeProvider modelRuntimeProvider;
    private final ABTestService abTestService;
    private final RecommendationCache cache;
    private final FeatureFlagService featureFlagService;
    private final RecommendationConverter converter;
    private final VariantRuntimeResolver variantRuntimeResolver;

    private static final FeatureFlagService NOOP_FLAGS =
            new FeatureFlagService((flag, id, props) -> Optional.empty());

    public RecommendationService(
            ModelRuntimeProvider modelRuntimeProvider,
            ABTestService abTestService
    ) {
        this(modelRuntimeProvider, abTestService, new RecommendationCacheProperties());
    }

    public RecommendationService(
            ModelRuntimeProvider modelRuntimeProvider,
            ABTestService abTestService,
            RecommendationCacheProperties cacheProperties
    ) {
        this(modelRuntimeProvider, abTestService, cacheProperties, NOOP_FLAGS);
    }

    public RecommendationService(
            ModelRuntimeProvider modelRuntimeProvider,
            ABTestService abTestService,
            RecommendationCacheProperties cacheProperties,
            FeatureFlagService featureFlagService
    ) {
        this(modelRuntimeProvider, abTestService, cacheProperties, featureFlagService, new RecommendationConverter());
    }

    public RecommendationService(
            ModelRuntimeProvider modelRuntimeProvider,
            ABTestService abTestService,
            RecommendationCacheProperties cacheProperties,
            FeatureFlagService featureFlagService,
            RecommendationConverter converter
    ) {
        this(modelRuntimeProvider, abTestService, cacheProperties, featureFlagService, converter,
                new VariantRuntimeResolver(modelRuntimeProvider, new SimpleMeterRegistry()));
    }

    @Autowired
    public RecommendationService(
            ModelRuntimeProvider modelRuntimeProvider,
            ABTestService abTestService,
            RecommendationCacheProperties cacheProperties,
            FeatureFlagService featureFlagService,
            RecommendationConverter converter,
            VariantRuntimeResolver variantRuntimeResolver
    ) {
        this.modelRuntimeProvider = modelRuntimeProvider;
        this.abTestService = abTestService;
        this.cache = new RecommendationCache(cacheProperties);
        this.featureFlagService = featureFlagService;
        this.converter = converter;
        this.variantRuntimeResolver = variantRuntimeResolver;
    }

    public RecommendResponse recommend(RecommendRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (request.getK() <= 0 || request.getK() > 100) {
            throw new IllegalArgumentException("k must be between 1 and 100");
        }
        return recommend(request, abTestService.getAssignmentForUser(request.getUserId()));
    }

    /**
     * Skips the A/B recomputation when the caller already holds the assignment.
     * The controller computes the assignment once and passes it here so the same hash
     * is not recalculated on the error path.
     */
    public RecommendResponse recommend(RecommendRequest request, ABTestService.Assignment assignment) {
        VariantRuntimeResolver.Resolved resolved =
                variantRuntimeResolver.resolve(assignment.variant(), abTestService.defaultVariant());
        ModelRuntime runtime = resolved.runtime();
        String servedVariant = resolved.servedVariant();
        String modelVersion = runtime.modelVersion();
        List<String> excludedItemIds = converter.normalizedExcludeItemIds(request);

        var cacheKey = new RecommendationCache.RecommendationKey(
                request.getUserId(), request.getK(), excludedItemIds, servedVariant, modelVersion);

        // getOrCompute ensures concurrent misses for the same key share a single computation.
        List<ScoredItem> items = cache.getOrCompute(cacheKey, () -> {
            // Cold-start requires BOTH the static property (fast kill switch) AND
            // the dynamic feature flag (supports per-user gradual rollout via PostHog).
            if (cache.isColdStartEnabled()
                    && isColdStartUser(request.getUserId(), runtime)
                    && featureFlagService.isEnabled(Flags.COLD_START_ENABLED, request.getUserId())) {
                return coldStartItems(request, runtime, servedVariant, modelVersion, excludedItemIds);
            }
            return computeRecommendations(request, runtime, excludedItemIds);
        });

        return converter.toResponse(request, modelVersion, servedVariant, items);
    }

    /** Returns a human-readable snapshot of cache hit/miss rates for monitoring. */
    public String cacheStats() {
        return "recommendations[" + cache.recommendationStats() + "] coldStart[" + cache.coldStartStats() + "]";
    }

    /** Returns structured cache stats for health endpoints and dashboards. */
    public CacheSnapshot cacheSnapshot() {
        RecommendationCache.CacheStats recommendations = cache.recommendationStats();
        RecommendationCache.CacheStats coldStart = cache.coldStartStats();
        return new CacheSnapshot(
                cache.isEnabled(),
                cache.isColdStartEnabled(),
                new CacheStatsSnapshot(recommendations.hits(), recommendations.misses(), recommendations.hitRate()),
                new CacheStatsSnapshot(coldStart.hits(), coldStart.misses(), coldStart.hitRate())
        );
    }

    /**
     * Degradation path: returns a cached response without acquiring a model-runtime slot.
     * Tries the per-user cache first, then the shared cold-start pool.
     * Returns empty when the cache is disabled or no entries exist for this user/variant.
     */
    public Optional<RecommendResponse> tryServeFromCache(RecommendRequest request) {
        if (!cache.isEnabled()) return Optional.empty();
        return tryServeFromCache(request, abTestService.getAssignmentForUser(request.getUserId()));
    }

    /**
     * Degradation path with a pre-computed assignment. Uses {@link ModelRuntimeProvider#getLoadedRuntime}
     * so a cold variant never triggers an ONNX load while the instance is already overloaded.
     */
    public Optional<RecommendResponse> tryServeFromCache(RecommendRequest request, ABTestService.Assignment assignment) {
        if (!cache.isEnabled()) return Optional.empty();
        ModelRuntime runtime = modelRuntimeProvider.getLoadedRuntime(assignment.variant());
        if (runtime == null) return Optional.empty();
        String modelVersion = runtime.modelVersion();
        List<String> excludedItemIds = converter.normalizedExcludeItemIds(request);

        // Degraded path keys on the ASSIGNED variant (not served): getLoadedRuntime never builds or
        // falls back here, so there is no served-variant distinction. Do NOT change to servedVariant —
        // the normal path keys on servedVariant, and these two must stay consistent per their own runtime.
        var cacheKey = new RecommendationCache.RecommendationKey(
                request.getUserId(), request.getK(), excludedItemIds, assignment.variant(), modelVersion);
        List<ScoredItem> items = cache.get(cacheKey);
        if (items != null) {
            return Optional.of(converter.toResponse(request, modelVersion, assignment.variant(), items));
        }

        if (cache.isColdStartEnabled()) {
            var coldStartKey = new RecommendationCache.ColdStartKey(assignment.variant(), modelVersion);
            List<ScoredItem> pool = cache.getColdStart(coldStartKey);
            if (pool != null) {
                return Optional.of(converter.toResponse(request, modelVersion, assignment.variant(),
                        limitAndExclude(pool, excludedItemIds, request.getK())));
            }
        }
        return Optional.empty();
    }

    private List<ScoredItem> coldStartItems(
            RecommendRequest request,
            ModelRuntime runtime,
            String servedVariant,
            String modelVersion,
            List<String> excludedItemIds
    ) {
        var coldStartKey = new RecommendationCache.ColdStartKey(servedVariant, modelVersion);
        // All unknown users share one pool per variant+modelVersion; compute it once.
        List<ScoredItem> pool = cache.getOrComputeColdStart(coldStartKey,
                () -> computeColdStartPool(request, runtime));
        return limitAndExclude(pool, excludedItemIds, request.getK());
    }

    private List<ScoredItem> computeRecommendations(
            RecommendRequest request,
            ModelRuntime runtime,
            List<String> excludedItemIds
    ) {
        FeatureEncoder.EncodedFeatures encoded = runtime.featureEncoder().encode(request);
        Set<String> excluded = excludedItemIds.isEmpty() ? Set.of() : new HashSet<>(excludedItemIds);
        int recallLimit = Math.min(Math.max(request.getK() * RECALL_MULTIPLIER, MIN_RECALL_SIZE), MAX_RECALL_LIMIT);
        var query = new RecommendationQuery(request.getUserId(), recallLimit, excluded, null);

        List<MovieCandidate> candidates = runtime.retrievalStage().retrieve(query, recallLimit);
        if (candidates.isEmpty()) {
            candidates = fallbackCandidates(runtime, parseUserId(request.getUserId()), excluded);
        }
        return runtime.rankingStage().rank(encoded, candidates, request.getK());
    }

    private List<ScoredItem> computeColdStartPool(RecommendRequest request, ModelRuntime runtime) {
        var coldStartRequest = new RecommendRequest();
        coldStartRequest.setUserId(request.getUserId());
        coldStartRequest.setK(cache.coldStartMaxK());

        FeatureEncoder.EncodedFeatures encoded = runtime.featureEncoder().encode(coldStartRequest);
        int recallLimit = Math.min(Math.max(cache.coldStartMaxK(), MIN_RECALL_SIZE), MAX_RECALL_LIMIT);
        var query = new RecommendationQuery(request.getUserId(), recallLimit, Set.of(), null);

        List<MovieCandidate> candidates = runtime.retrievalStage().retrieve(query, recallLimit);
        if (candidates.isEmpty()) {
            candidates = fallbackCandidates(runtime, null, Set.of());
        }
        return runtime.rankingStage().rank(encoded, candidates, cache.coldStartMaxK());
    }

    /**
     * Redis-down fallback: the in-memory CandidateSelectionService pool, wrapped as MovieCandidates
     * (score 0.0 — these are all in-vocab so the RankingStage re-scores them via ONNX, tier 1).
     */
    private static List<MovieCandidate> fallbackCandidates(ModelRuntime runtime, Integer numericUserId, Set<String> excluded) {
        Set<String> ids = new CandidateSelectionService(runtime.artifactService()).selectCandidates(numericUserId, excluded);
        List<MovieCandidate> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(new MovieCandidate(id, 0.0, "fallback", Map.of()));
        }
        return out;
    }

    private static Integer parseUserId(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            return Integer.parseInt(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isColdStartUser(String userId, ModelRuntime runtime) {
        Map<String, Integer> userVocab = runtime.artifactService().getUserVocab();
        return userVocab != null && !userVocab.containsKey(userId);
    }

    private static List<ScoredItem> limitAndExclude(List<ScoredItem> items, List<String> excludedItemIds, int k) {
        if (items.isEmpty() || k <= 0) {
            return List.of();
        }
        Set<String> excluded = excludedItemIds.isEmpty() ? Set.of() : new HashSet<>(excludedItemIds);
        return items.stream()
                .filter(item -> !excluded.contains(item.itemId()))
                .limit(k)
                .toList();
    }

    public record CacheSnapshot(
            boolean enabled,
            boolean coldStartEnabled,
            CacheStatsSnapshot recommendations,
            CacheStatsSnapshot coldStart
    ) {}

    public record CacheStatsSnapshot(long hits, long misses, double hitRate) {}
}
