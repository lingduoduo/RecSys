package com.recsys.recommendation.feedback;

import java.time.Instant;
import java.util.Map;

public record FeedbackEvent(
        String userId,
        String itemId,
        String eventType,
        Instant occurredAt,
        Map<String, Object> attributes
) {
    public FeedbackEvent {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }
}
