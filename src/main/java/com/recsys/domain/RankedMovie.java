package com.recsys.domain;

import java.util.Map;

public record RankedMovie(
        String itemId,
        double score,
        int rank,
        Map<String, Object> features
) {
    public RankedMovie {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("score must be a valid number");
        }
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive");
        }
        features = features == null || features.isEmpty() ? Map.of() : Map.copyOf(features);
    }
}
