package com.recsys.online.learner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.online.event.EventSemantics;
import com.recsys.online.event.LogCollector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OnlineJoiner {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JoinedSample joinJson(String behaviorLogJson,
                                 Map<String, String> userFeatures,
                                 Map<String, String> itemFeatures,
                                 Map<String, String> contextFeatures) {
        try {
            LogCollector.UserBehaviorLog log = MAPPER.readValue(
                    behaviorLogJson, LogCollector.UserBehaviorLog.class);
            return join(log, userFeatures, itemFeatures, contextFeatures);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unable to parse behavior log JSON", e);
        }
    }

    public JoinedSample join(LogCollector.UserBehaviorLog log,
                             Map<String, String> userFeatures,
                             Map<String, String> itemFeatures,
                             Map<String, String> contextFeatures) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        if (log.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (log.eventId() == null || log.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }

        String eventType = EventSemantics.normalizeEventType(log.eventType());
        if (!EventSemantics.isSearch(eventType) && log.movieId() <= 0) {
            throw new IllegalArgumentException("movieId must be positive");
        }
        Map<String, String> features = new LinkedHashMap<>();
        putAll(features, "user.", userFeatures);
        putAll(features, "item.", itemFeatures);
        putAll(features, "context.", contextFeatures);
        putAll(features, "event.", log.features());
        features.put("event.source", blankToDefault(log.source(), "unknown"));
        features.put("event.watchMs", Long.toString(Math.max(0L, log.watchMs())));
        features.put("event.dwellMs", Long.toString(Math.max(0L, log.dwellMs())));
        if (log.rating() != null) {
            features.put("event.rating", Integer.toString(log.rating()));
        }

        return new JoinedSample(
                log.eventId(),
                log.userId(),
                log.movieId(),
                eventType,
                EventSemantics.labelFor(eventType, eventDurationMs(eventType, log), log.rating()),
                Math.max(0L, log.eventTimeMillis()),
                Collections.unmodifiableMap(new LinkedHashMap<>(features))
        );
    }

    public String toJson(JoinedSample sample) {
        try {
            return MAPPER.writeValueAsString(sample);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unable to serialize joined sample", e);
        }
    }

    private static void putAll(Map<String, String> target, String prefix, Map<String, String> source) {
        LogCollector.sanitizeFeatures(source).forEach((key, value) -> target.put(prefix + key, value));
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static long eventDurationMs(String eventType, LogCollector.UserBehaviorLog log) {
        return EventSemantics.isDwell(eventType) ? log.dwellMs() : log.watchMs();
    }

    public record JoinedSample(String eventId,
                               int userId,
                               int movieId,
                               String eventType,
                               int label,
                               long eventTimeMillis,
                               Map<String, String> features) {}
}
