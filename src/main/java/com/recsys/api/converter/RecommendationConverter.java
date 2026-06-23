package com.recsys.api.converter;

import com.recsys.domain.prediction.ScoredItem;
import com.recsys.api.request.RecommendRequest;
import com.recsys.api.response.RecommendResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TreeSet;

@Component
public class RecommendationConverter {

    public RecommendResponse toResponse(
            RecommendRequest request,
            String modelVersion,
            String variant,
            List<ScoredItem> items
    ) {
        return new RecommendResponse(request.getUserId(), modelVersion, variant, items);
    }

    public List<String> normalizedExcludeItemIds(RecommendRequest request) {
        if (request.getExcludeItemIds() == null || request.getExcludeItemIds().isEmpty()) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(request.getExcludeItemIds()));
    }
}
