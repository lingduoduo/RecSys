package com.recsys.application.recommendation;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.application.pagination.RecommendationPaginationCoordinator;
import com.recsys.application.pagination.RecommendationPaginationCoordinator.DecodedRequest;
import com.recsys.application.pagination.RecommendationPaginationCoordinator.RecommendationPage;
import com.recsys.application.ranking.CandidateRanker;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RecommendationOrchestrator implements RecommendationPipeline {
    private final MultiChannelRecallService recallService;
    private final CandidateRanker ranker;
    private final RecommendationHydrator hydrator;
    private final RecommendationPaginationCoordinator pagination;
    private final int maxCandidates;

    public RecommendationOrchestrator(
            MultiChannelRecallService recallService,
            CandidateRanker ranker,
            RecommendationHydrator hydrator,
            RecommendationPaginationCoordinator pagination,
            int maxCandidates
    ) {
        this.recallService = Objects.requireNonNull(recallService, "recallService");
        this.ranker = Objects.requireNonNull(ranker, "ranker");
        this.hydrator = hydrator == null ? RecommendationHydrator.IDENTITY : hydrator;
        this.pagination = Objects.requireNonNull(pagination, "pagination");
        this.maxCandidates = maxCandidates;
    }

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        Objects.requireNonNull(query, "query");
        DecodedRequest decoded = pagination.decode(query);
        int windowLimit = maxCandidates;
        RecallResult recall = recallService.recallDetailed(query, windowLimit);
        List<MovieCandidate> candidates = recall.candidates();
        List<RankedMovie> ranked = ranker.rank(query, candidates, windowLimit);
        boolean sourceTruncated = ranked.size() == maxCandidates;
        RecommendationPage page = pagination.page(decoded, ranked, sourceTruncated);
        List<RankedMovie> hydrated = hydrator.hydrate(query, page.items());

        Map<String, String> trace = new java.util.LinkedHashMap<>();
        trace.put("candidateCount", Integer.toString(candidates.size()));
        trace.put("rankedCount", Integer.toString(ranked.size()));
        if (page.budgetExhausted()) {
            trace.put("paginationBudgetExhausted", "true");
        }
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
                query.userId(), hydrated, page.nextCursor(), page.hasMore(), trace);
    }
}
