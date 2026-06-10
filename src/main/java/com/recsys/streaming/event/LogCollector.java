package com.recsys.streaming.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class LogCollector {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String DEFAULT_TOPIC = "movie_events";
    public static final String SCHEMA_VERSION = "user-event-v2";

    private final Clock clock;
    private final String topic;

    public LogCollector() {
        this(Clock.systemUTC());
    }

    public LogCollector(Clock clock) {
        this(clock, DEFAULT_TOPIC);
    }

    public LogCollector(Clock clock, String topic) {
        this.clock = clock;
        this.topic = topic == null || topic.isBlank() ? DEFAULT_TOPIC : topic.trim();
    }

    public String collect(UserBehaviorLog log) {
        UserBehaviorLog normalized = normalize(log);
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unable to serialize behavior log", e);
        }
    }

    public KafkaEvent collectForKafka(UserBehaviorLog log) {
        UserBehaviorLog normalized = normalize(log);
        return new KafkaEvent(
                topic,
                kafkaKey(normalized),
                collect(normalized),
                Map.of(
                        "schemaVersion", SCHEMA_VERSION,
                        "eventType", normalized.eventType(),
                        "source", normalized.source()
                )
        );
    }

    private UserBehaviorLog normalize(UserBehaviorLog log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        if (log.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String eventType = EventSemantics.normalizeEventType(log.eventType());
        if (!EventSemantics.isSearch(eventType) && log.movieId() <= 0) {
            throw new IllegalArgumentException("movieId must be positive");
        }
        String eventId = (log.eventId() == null || log.eventId().isBlank())
                ? UUID.randomUUID().toString()
                : log.eventId().trim();
        long eventTimeMillis = log.eventTimeMillis() > 0 ? log.eventTimeMillis() : clock.millis();
        String source = (log.source() == null || log.source().isBlank()) ? "unknown" : log.source().trim();
        Map<String, String> features = sanitizeFeatures(log.features());
        long watchMs = Math.max(0L, log.watchMs());
        long dwellMs = Math.max(0L, log.dwellMs());
        if (EventSemantics.isDwell(eventType) && dwellMs == 0L) {
            dwellMs = watchMs;
        }

        return new UserBehaviorLog(
                eventId,
                log.userId(),
                Math.max(0, log.movieId()),
                eventType,
                watchMs,
                dwellMs,
                log.rating(),
                eventTimeMillis,
                source,
                features
        );
    }

    public static Map<String, String> sanitizeFeatures(Map<String, String> features) {
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
        return Collections.unmodifiableMap(sorted);
    }

    private static String kafkaKey(UserBehaviorLog log) {
        return "user:" + log.userId();
    }

    public record UserBehaviorLog(String eventId,
                                  int userId,
                                  int movieId,
                                  String eventType,
                                  long watchMs,
                                  long dwellMs,
                                  Integer rating,
                                  long eventTimeMillis,
                                  String source,
                                  Map<String, String> features) {
        public UserBehaviorLog(String eventId,
                               int userId,
                               int movieId,
                               String eventType,
                               long watchMs,
                               Integer rating,
                               long eventTimeMillis,
                               String source,
                               Map<String, String> features) {
            this(eventId, userId, movieId, eventType, watchMs, 0L, rating,
                    eventTimeMillis, source, features);
        }
    }

    public record KafkaEvent(String topic,
                             String key,
                             String value,
                             Map<String, String> headers) {}
}
