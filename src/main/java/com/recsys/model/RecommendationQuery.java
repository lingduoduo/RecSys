package com.recsys.model;

import java.util.Set;
import java.util.TreeSet;

public record RecommendationQuery(
        String userId,
        int limit,
        Set<String> excludedItemIds,
        String cursor
) {
    public RecommendationQuery {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        excludedItemIds = excludedItemIds == null || excludedItemIds.isEmpty()
                ? Set.of()
                : Set.copyOf(new TreeSet<>(excludedItemIds));
        cursor = cursor == null || cursor.isBlank() ? null : cursor.trim();
    }
}
