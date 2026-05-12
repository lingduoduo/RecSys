package com.recsys.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class LogCollector {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;

    public LogCollector() {
        this(Clock.systemUTC());
    }

    public LogCollector(Clock clock) {
        this.clock = clock;
    }

    public String collect(UserBehaviorLog log) {
        UserBehaviorLog normalized = normalize(log);
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unable to serialize behavior log", e);
        }
    }

    private UserBehaviorLog normalize(UserBehaviorLog log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        if (log.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (log.movieId() <= 0) {
            throw new IllegalArgumentException("movieId must be positive");
        }

        String eventType = EventSemantics.normalizeEventType(log.eventType());
        String eventId = (log.eventId() == null || log.eventId().isBlank())
                ? UUID.randomUUID().toString()
                : log.eventId().trim();
        long eventTimeMillis = log.eventTimeMillis() > 0 ? log.eventTimeMillis() : clock.millis();
        String source = (log.source() == null || log.source().isBlank()) ? "unknown" : log.source().trim();
        Map<String, String> features = sanitizeFeatures(log.features());

        return new UserBehaviorLog(
                eventId,
                log.userId(),
                log.movieId(),
                eventType,
                Math.max(0L, log.watchMs()),
                log.rating(),
                eventTimeMillis,
                source,
                features
        );
    }

    static Map<String, String> sanitizeFeatures(Map<String, String> features) {
        if (features == null || features.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sorted = new TreeMap<>();
        features.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            sorted.put(key.trim(), value.trim());
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public record UserBehaviorLog(String eventId,
                                  int userId,
                                  int movieId,
                                  String eventType,
                                  long watchMs,
                                  Integer rating,
                                  long eventTimeMillis,
                                  String source,
                                  Map<String, String> features) {}
}
