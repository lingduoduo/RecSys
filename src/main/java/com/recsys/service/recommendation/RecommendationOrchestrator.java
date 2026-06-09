package com.recsys.service.recommendation;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.service.hydrator.RecommendationHydrator;
import com.recsys.service.pagination.CursorPaginationService;
import com.recsys.service.pagination.Page;
import com.recsys.service.ranking.CandidateRanker;
import com.recsys.service.retrieval.MultiChannelRecallService;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RecommendationOrchestrator {
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

    public RecommendationResult recommend(RecommendationQuery query) {
        Objects.requireNonNull(query, "query");
        int windowLimit = query.limit() * recallMultiplier;
        List<MovieCandidate> candidates = recallService.recall(query, windowLimit);
        List<RankedMovie> ranked = ranker.rank(query, candidates, windowLimit);
        Page<RankedMovie> page = paginationService.page(ranked, query.cursor(), query.limit());
        List<RankedMovie> hydrated = hydrator.hydrate(query, page.items());

        return new RecommendationResult(
                query.userId(),
                hydrated,
                page.nextCursor(),
                Map.of(
                        "candidateCount", Integer.toString(candidates.size()),
                        "rankedCount", Integer.toString(ranked.size())
                )
        );
    }
}
