package com.recsys.application.model;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.pagination.RecommendationPaginationCoordinator;
import com.recsys.application.pagination.RecommendationPaginationCoordinator.DecodedRequest;
import com.recsys.application.pagination.RecommendationPaginationCoordinator.RecommendationPage;
import com.recsys.application.recommendation.RecommendationService;
import com.recsys.application.recommendation.RecommendationWindow;

import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.domain.prediction.ScoredItem;
import com.recsys.api.request.RecommendRequest;
import com.recsys.api.response.RecommendResponse;
import com.recsys.application.recommendation.RecommendationPipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class OnnxInferencePipeline implements RecommendationPipeline {

    private final RecommendationService recommendationService;
    private final ABTestService abTestService;
    private final RecommendationPaginationCoordinator pagination;
    private final int maxCandidates;

    public OnnxInferencePipeline(RecommendationService recommendationService,
                                 ABTestService abTestService,
                                 RecommendationPaginationCoordinator pagination,
                                 int maxCandidates) {
        this.recommendationService =
                Objects.requireNonNull(recommendationService, "recommendationService");
        this.abTestService = Objects.requireNonNull(abTestService, "abTestService");
        this.pagination = Objects.requireNonNull(pagination, "pagination");
        this.maxCandidates = maxCandidates;
    }

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        DecodedRequest decoded = pagination.decode(query);
        ABTestService.Assignment assignment =
                abTestService.getAssignmentForUser(query.userId());
        RecommendRequest request = toRequest(query);
        RecommendationWindow window =
                recommendationService.recommendWindow(request, assignment, maxCandidates);
        RecommendResponse response = window.response();
        List<RankedMovie> ranked = toRanked(response.recommendations());
        RecommendationPage page = pagination.page(decoded, ranked, window.sourceTruncated());
        return toResult(query.userId(), response, assignment.variant(), page);
    }

    private static RecommendRequest toRequest(RecommendationQuery query) {
        RecommendRequest request = new RecommendRequest();
        request.setUserId(query.userId());
        request.setK(query.limit());
        if (!query.excludedItemIds().isEmpty()) {
            request.setExcludeItemIds(new ArrayList<>(query.excludedItemIds()));
        }
        return request;
    }

    private static List<RankedMovie> toRanked(List<ScoredItem> raw) {
        List<ScoredItem> ordered = raw.stream()
                .sorted(Comparator.comparingDouble(ScoredItem::score).reversed()
                        .thenComparing(ScoredItem::itemId))
                .toList();
        List<RankedMovie> items = new ArrayList<>(raw.size());
        for (int i = 0; i < ordered.size(); i++) {
            ScoredItem item = ordered.get(i);
            items.add(new RankedMovie(item.itemId(), item.score(), i + 1, Map.of()));
        }
        return items;
    }

    private static RecommendationResult toResult(
            String userId,
            RecommendResponse response,
            String assignmentVariant,
            RecommendationPage page
    ) {
        Map<String, String> trace = new java.util.LinkedHashMap<>();
        trace.put("abTestVariant", assignmentVariant != null ? assignmentVariant : "");
        trace.put("modelVersion", response.modelVersion() != null ? response.modelVersion() : "");
        if (page.budgetExhausted()) {
            trace.put("paginationBudgetExhausted", "true");
        }
        return new RecommendationResult(
                userId, page.items(), page.nextCursor(), page.hasMore(), trace);
    }
}
