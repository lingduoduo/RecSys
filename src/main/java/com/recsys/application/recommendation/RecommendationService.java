package com.recsys.application.recommendation;
import com.recsys.application.feature.FeatureEncoder;
import com.recsys.application.model.ModelRuntime;
import com.recsys.application.experiment.VariantRuntimeResolver;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.model.ModelRuntimeProvider;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.featureflags.FeatureFlagService;
import com.recsys.infrastructure.featureflags.Flags;
import com.recsys.config.RecommendationCacheProperties;
import com.recsys.api.converter.RecommendationConverter;
import com.recsys.api.request.RecommendRequest;
import com.recsys.api.response.RecommendResponse;
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
        int candidateBudget =
                Math.min(Math.max(request.getK() * RECALL_MULTIPLIER, MIN_RECALL_SIZE), MAX_RECALL_LIMIT);
        return computeWindow(request, assignment, candidateBudget).response();
    }

    /**
     * Produces the bounded ranked window consumed by cursor pagination. The public page size stays
     * capped at 100, while this internal path may inspect a larger configured candidate budget.
     */
    public RecommendationWindow recommendWindow(
            RecommendRequest pageRequest,
            ABTestService.Assignment assignment,
            int candidateBudget
    ) {
        if (candidateBudget < 1 || candidateBudget > 10_000) {
            throw new IllegalArgumentException("candidateBudget must be between 1 and 10000");
        }
        RecommendRequest windowRequest = new RecommendRequest();
        windowRequest.setUserId(pageRequest.getUserId());
        windowRequest.setK(candidateBudget);
        windowRequest.setExcludeItemIds(pageRequest.getExcludeItemIds());
        return computeWindow(windowRequest, assignment, candidateBudget);
    }

    private RecommendationWindow computeWindow(
            RecommendRequest request,
            ABTestService.Assignment assignment,
            int candidateBudget
    ) {
        VariantRuntimeResolver.Resolved resolved =
                variantRuntimeResolver.resolve(assignment.variant(), abTestService.defaultVariant());
        ModelRuntime runtime = resolved.runtime();
        String servedVariant = resolved.servedVariant();
        String modelVersion = runtime.modelVersion();
        List<String> excludedItemIds = converter.normalizedExcludeItemIds(request);

        var cacheKey = new RecommendationCache.RecommendationKey(
                request.getUserId(), request.getK(), excludedItemIds, servedVariant, modelVersion);

        // getOrCompute ensures concurrent misses for the same key share a single computation.
        RecommendationCache.RankedWindow rankedWindow = cache.getOrCompute(cacheKey, () -> {
            // Cold-start requires BOTH the static property (fast kill switch) AND
            // the dynamic feature flag (supports per-user gradual rollout via PostHog).
            if (cache.isColdStartEnabled()
                    && isColdStartUser(request.getUserId(), runtime)
                    && featureFlagService.isEnabled(Flags.COLD_START_ENABLED, request.getUserId())) {
                return coldStartItems(
                        request,
                        runtime,
                        servedVariant,
                        modelVersion,
                        excludedItemIds,
                        candidateBudget);
            }
            return computeRecommendations(request, runtime, excludedItemIds, candidateBudget);
        });

        RecommendResponse response =
                converter.toResponse(request, modelVersion, servedVariant, rankedWindow.items());
        return new RecommendationWindow(response, rankedWindow.sourceTruncated());
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
     * Degradation path with a pre-computed assignment; resolves the control variant itself so the
     * V1 controller keeps the fallback below without knowing about it.
     */
    public Optional<RecommendResponse> tryServeFromCache(RecommendRequest request, ABTestService.Assignment assignment) {
        return tryServeFromCache(request, assignment, abTestService.defaultVariant());
    }

    /**
     * Degradation path. Mirrors normal resolution without any cold work: the assigned variant's
     * cache is used when that runtime is already loaded and not in failure cooldown; otherwise the
     * loaded control runtime's cache is used, keyed and attributed as control. Only
     * {@link ModelRuntimeProvider#getLoadedRuntime} is ever consulted — an overloaded instance
     * must never pay an ONNX build on the path that exists to shed load.
     */
    public Optional<RecommendResponse> tryServeFromCache(RecommendRequest request,
                                                         ABTestService.Assignment assignment,
                                                         String defaultVariant) {
        if (!cache.isEnabled()) return Optional.empty();
        List<String> excludedItemIds = converter.normalizedExcludeItemIds(request);
        String assigned = assignment.variant();

        ModelRuntime assignedRuntime = variantRuntimeResolver.isInCooldown(assigned)
                ? null
                : modelRuntimeProvider.getLoadedRuntime(assigned);
        if (assignedRuntime != null) {
            return cachedResponseForRuntime(request, assignedRuntime, assigned, excludedItemIds);
        }
        if (assigned.equals(defaultVariant)) {
            return Optional.empty();
        }
        ModelRuntime control = modelRuntimeProvider.getLoadedRuntime(defaultVariant);
        if (control == null) {
            return Optional.empty();
        }
        return cachedResponseForRuntime(request, control, defaultVariant, excludedItemIds);
    }

    /**
     * Cache lookup for one runtime. Keyed on {@code servedVariant} exactly as the normal path keys
     * on {@code resolved.servedVariant()} — the assignment string when the assigned runtime serves,
     * the configured default string when control does — so both paths hit the same entries.
     */
    private Optional<RecommendResponse> cachedResponseForRuntime(RecommendRequest request,
                                                                 ModelRuntime runtime,
                                                                 String servedVariant,
                                                                 List<String> excludedItemIds) {
        String modelVersion = runtime.modelVersion();
        var cacheKey = new RecommendationCache.RecommendationKey(
                request.getUserId(), request.getK(), excludedItemIds, servedVariant, modelVersion);
        RecommendationCache.RankedWindow rankedWindow = cache.get(cacheKey);
        if (rankedWindow != null) {
            return Optional.of(converter.toResponse(request, modelVersion, servedVariant, rankedWindow.items()));
        }

        if (cache.isColdStartEnabled()) {
            int candidateBudget =
                    Math.min(Math.max(request.getK() * RECALL_MULTIPLIER, MIN_RECALL_SIZE), MAX_RECALL_LIMIT);
            int poolBudget = Math.max(cache.coldStartMaxK(), candidateBudget);
            var coldStartKey = new RecommendationCache.ColdStartKey(servedVariant, modelVersion, poolBudget);
            RecommendationCache.RankedWindow pool = cache.getColdStart(coldStartKey);
            if (pool != null) {
                return Optional.of(converter.toResponse(request, modelVersion, servedVariant,
                        limitAndExclude(pool.items(), excludedItemIds, request.getK())));
            }
        }
        return Optional.empty();
    }

    private RecommendationCache.RankedWindow coldStartItems(
            RecommendRequest request,
            ModelRuntime runtime,
            String servedVariant,
            String modelVersion,
            List<String> excludedItemIds,
            int candidateBudget
    ) {
        int poolBudget = Math.max(cache.coldStartMaxK(), candidateBudget);
        var coldStartKey =
                new RecommendationCache.ColdStartKey(servedVariant, modelVersion, poolBudget);
        // All unknown users share one pool for each variant, model version, and acquisition budget.
        RecommendationCache.RankedWindow pool = cache.getOrComputeColdStart(
                coldStartKey,
                () -> computeColdStartPool(request, runtime, poolBudget));
        return new RecommendationCache.RankedWindow(
                limitAndExclude(pool.items(), excludedItemIds, request.getK()),
                pool.sourceTruncated());
    }

    private RecommendationCache.RankedWindow computeRecommendations(
            RecommendRequest request,
            ModelRuntime runtime,
            List<String> excludedItemIds,
            int candidateBudget
    ) {
        FeatureEncoder.EncodedFeatures encoded = runtime.featureEncoder().encode(request);
        Set<String> excluded = excludedItemIds.isEmpty() ? Set.of() : new HashSet<>(excludedItemIds);
        int queryPageLimit = Math.min(candidateBudget, MAX_RECALL_LIMIT);
        var query = new RecommendationQuery(request.getUserId(), queryPageLimit, excluded, null);

        List<MovieCandidate> candidates = runtime.retrievalStage().retrieve(query, candidateBudget);
        if (candidates.isEmpty()) {
            candidates = fallbackCandidates(runtime, parseUserId(request.getUserId()), excluded);
        }
        boolean sourceTruncated = candidates.size() >= candidateBudget;
        List<ScoredItem> ranked = runtime.rankingStage().rank(encoded, candidates, request.getK());
        return new RecommendationCache.RankedWindow(ranked, sourceTruncated);
    }

    private RecommendationCache.RankedWindow computeColdStartPool(
            RecommendRequest request,
            ModelRuntime runtime,
            int candidateBudget
    ) {
        var coldStartRequest = new RecommendRequest();
        coldStartRequest.setUserId(request.getUserId());
        coldStartRequest.setK(candidateBudget);

        FeatureEncoder.EncodedFeatures encoded = runtime.featureEncoder().encode(coldStartRequest);
        int queryPageLimit = Math.min(candidateBudget, MAX_RECALL_LIMIT);
        var query = new RecommendationQuery(request.getUserId(), queryPageLimit, Set.of(), null);

        List<MovieCandidate> candidates =
                runtime.retrievalStage().retrieve(query, candidateBudget);
        if (candidates.isEmpty()) {
            candidates = fallbackCandidates(runtime, null, Set.of());
        }
        boolean sourceTruncated = candidates.size() >= candidateBudget;
        List<ScoredItem> ranked =
                runtime.rankingStage().rank(encoded, candidates, candidateBudget);
        return new RecommendationCache.RankedWindow(ranked, sourceTruncated);
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
