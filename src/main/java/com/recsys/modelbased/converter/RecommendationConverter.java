package com.recsys.modelbased.converter;

import com.recsys.modelbased.dto.ScoredItem;
import com.recsys.modelbased.request.RecommendRequest;
import com.recsys.modelbased.response.RecommendResponse;
import com.recsys.modelbased.service.ABTestService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TreeSet;

@Component
public class RecommendationConverter {

    public RecommendResponse toResponse(
            RecommendRequest request,
            String modelVersion,
            ABTestService.Assignment assignment,
            List<ScoredItem> items
    ) {
        return new RecommendResponse(request.getUserId(), modelVersion, assignment.variant(), items);
    }

    public List<String> normalizedExcludeItemIds(RecommendRequest request) {
        if (request.getExcludeItemIds() == null || request.getExcludeItemIds().isEmpty()) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(request.getExcludeItemIds()));
    }
}
