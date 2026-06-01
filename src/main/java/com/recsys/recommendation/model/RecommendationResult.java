package com.recsys.recommendation.model;

import java.util.List;
import java.util.Map;

public record RecommendationResult(
        String userId,
        List<RankedMovie> items,
        String nextCursor,
        Map<String, String> trace
) {
    public RecommendationResult {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor.trim();
        trace = trace == null || trace.isEmpty() ? Map.of() : Map.copyOf(trace);
    }
}
