package com.recsys.domain.recommendation;

import com.recsys.domain.item.RankedMovie;
import java.util.List;
import java.util.Map;

public record RecommendationResult(
        String userId,
        List<RankedMovie> items,
        String nextCursor,
        boolean hasMore,
        Map<String, String> trace
) {
    public RecommendationResult {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor.trim();
        if (hasMore != (nextCursor != null)) {
            throw new IllegalArgumentException("hasMore and nextCursor must agree");
        }
        trace = trace == null || trace.isEmpty() ? Map.of() : Map.copyOf(trace);
    }
}
