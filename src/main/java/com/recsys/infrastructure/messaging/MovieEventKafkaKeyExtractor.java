package com.recsys.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

public final class MovieEventKafkaKeyExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MovieEventKafkaKeyExtractor() {
    }

    public static Optional<String> extract(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            JsonNode userId = root == null ? null : root.get("userId");
            if (userId == null || userId.isNull()) {
                userId = root == null ? null : root.get("user_id");
            }
            return extractPositiveId(userId);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> extractPositiveId(JsonNode userId) {
        if (userId == null || (!userId.isIntegralNumber() && !userId.isTextual())) {
            return Optional.empty();
        }
        String candidate = userId.asText();
        int separator = candidate.lastIndexOf('_');
        if (separator >= 0) {
            candidate = candidate.substring(separator + 1);
        }
        if (candidate.isEmpty() || !candidate.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        try {
            long id = Long.parseLong(candidate);
            return id > 0 ? Optional.of(Long.toString(id)) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
