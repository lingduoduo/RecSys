package com.recsys.recommendation.model;

import java.util.Map;

public record MovieCandidate(
        String itemId,
        double score,
        String channel,
        Map<String, Object> features
) {
    public MovieCandidate {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("score must be a valid number");
        }
        channel = channel == null || channel.isBlank() ? "unknown" : channel;
        features = features == null || features.isEmpty() ? Map.of() : Map.copyOf(features);
    }
}
