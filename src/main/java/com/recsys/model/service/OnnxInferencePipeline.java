package com.recsys.model.service;

import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.model.dto.ScoredItem;
import com.recsys.model.request.RecommendRequest;
import com.recsys.model.response.RecommendResponse;
import com.recsys.service.recommendation.RecommendationPipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OnnxInferencePipeline implements RecommendationPipeline {

    private final RecommendationService recommendationService;
    private final ABTestService abTestService;

    public OnnxInferencePipeline(RecommendationService recommendationService,
                                  ABTestService abTestService) {
        this.recommendationService = recommendationService;
        this.abTestService = abTestService;
    }

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        ABTestService.Assignment assignment =
                abTestService.getAssignmentForUser(query.userId());
        RecommendRequest request = toRequest(query);
        RecommendResponse response = recommendationService.recommend(request, assignment);
        return toResult(query.userId(), response);
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

    private static RecommendationResult toResult(String userId, RecommendResponse response) {
        List<ScoredItem> raw = response.recommendations();
        List<RankedMovie> items = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            ScoredItem item = raw.get(i);
            items.add(new RankedMovie(item.itemId(), item.score(), i + 1, Map.of()));
        }
        Map<String, String> trace = Map.of(
                "abTestVariant", response.abTestVariant() != null ? response.abTestVariant() : "",
                "modelVersion",  response.modelVersion()  != null ? response.modelVersion()  : "");
        return new RecommendationResult(userId, items, null, trace);
    }
}
