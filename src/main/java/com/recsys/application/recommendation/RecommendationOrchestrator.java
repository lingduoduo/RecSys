package com.recsys.application.recommendation;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.application.recommendation.RecommendationHydrator;
import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.pagination.Page;
import com.recsys.application.ranking.CandidateRanker;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RecommendationOrchestrator implements RecommendationPipeline {
    private static final int DEFAULT_RECALL_MULTIPLIER = 5;

    private final MultiChannelRecallService recallService;
    private final CandidateRanker ranker;
    private final RecommendationHydrator hydrator;
    private final CursorPaginationService paginationService;
    private final int recallMultiplier;

    public RecommendationOrchestrator(
            MultiChannelRecallService recallService,
            CandidateRanker ranker,
            RecommendationHydrator hydrator,
            CursorPaginationService paginationService
    ) {
        this(recallService, ranker, hydrator, paginationService, DEFAULT_RECALL_MULTIPLIER);
    }

    public RecommendationOrchestrator(
            MultiChannelRecallService recallService,
            CandidateRanker ranker,
            RecommendationHydrator hydrator,
            CursorPaginationService paginationService,
            int recallMultiplier
    ) {
        this.recallService = Objects.requireNonNull(recallService, "recallService");
        this.ranker = Objects.requireNonNull(ranker, "ranker");
        this.hydrator = hydrator == null ? RecommendationHydrator.IDENTITY : hydrator;
        this.paginationService = Objects.requireNonNull(paginationService, "paginationService");
        this.recallMultiplier = Math.max(1, recallMultiplier);
    }

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        Objects.requireNonNull(query, "query");
        int windowLimit = query.limit() * recallMultiplier;
        com.recsys.application.retrieval.multichannel.RecallResult recall =
                recallService.recallDetailed(query, windowLimit);
        List<MovieCandidate> candidates = recall.candidates();
        List<RankedMovie> ranked = ranker.rank(query, candidates, windowLimit);
        Page<RankedMovie> page = paginationService.page(
                ranked, query.cursor(), query.limit(), RankedMovie::score, RankedMovie::itemId);
        List<RankedMovie> hydrated = hydrator.hydrate(query, page.items());

        Map<String, String> trace = new java.util.LinkedHashMap<>();
        trace.put("candidateCount", Integer.toString(candidates.size()));
        trace.put("rankedCount", Integer.toString(ranked.size()));
        if (!recall.degradedChannels().isEmpty()) {
            // Sorted alphabetically so this value stays deterministic across JVM runs and
            // matches the X-Recall-Degraded header produced by
            // BaseApiService#writeJsonWithRecallDegraded (which also sorts before joining).
            // That second sort re-sorts an already-sorted CSV split back into a set — a
            // harmless no-op kept for defense-in-depth/API independence, not removed here.
            trace.put("degradedChannels", recall.degradedChannels().stream()
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        if (recall.outcome()
                != com.recsys.application.retrieval.multichannel.RecallResult.DegradationOutcome.HEALTHY) {
            trace.put("degradationOutcome", recall.outcome().wireValue());
        }

        return new RecommendationResult(
                query.userId(), hydrated, page.nextCursor(), page.nextCursor() != null, trace);
    }
}
