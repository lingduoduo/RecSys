package com.recsys.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.math.BigDecimal;

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
        if (userId == null || (!userId.isNumber() && !userId.isTextual())) {
            return Optional.empty();
        }
        String candidate = userId.asText();
        if (userId.isTextual()) {
            int separator = candidate.lastIndexOf('_');
            if (separator >= 0) candidate = candidate.substring(separator + 1);
            if (candidate.isEmpty() || !candidate.chars().allMatch(Character::isDigit)) return Optional.empty();
        }
        try {
            int id = new BigDecimal(candidate).intValueExact();
            return id > 0 ? Optional.of(Integer.toString(id)) : Optional.empty();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
