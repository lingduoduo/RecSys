package com.recsys.online.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogCollectorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void collectNormalizesBehaviorLogForKafkaPayload() throws Exception {
        LogCollector collector = new LogCollector(Clock.fixed(
                Instant.ofEpochMilli(1713503000000L), ZoneOffset.UTC));

        String json = collector.collect(new LogCollector.UserBehaviorLog(
                " evt-1 ",
                123,
                9,
                " CLICK ",
                -1L,
                null,
                0L,
                " mobile ",
                Map.of("rank", "4", "requestId", "req-9")
        ));

        JsonNode node = MAPPER.readTree(json);
        assertEquals("evt-1", node.get("eventId").asText());
        assertEquals(123, node.get("userId").asInt());
        assertEquals(9, node.get("movieId").asInt());
        assertEquals("click", node.get("eventType").asText());
        assertEquals(0L, node.get("watchMs").asLong());
        assertEquals(0L, node.get("dwellMs").asLong());
        assertEquals(1713503000000L, node.get("eventTimeMillis").asLong());
        assertEquals("mobile", node.get("source").asText());
        assertEquals("4", node.get("features").get("rank").asText());
    }

    @Test
    void collectRejectsUnsupportedEventTypes() {
        LogCollector collector = new LogCollector();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                collector.collect(new LogCollector.UserBehaviorLog(
                        null, 123, 9, "share", 0L, null, 0L, "web", Map.of())));

        assertFalse(ex.getMessage().isBlank());
    }

    @Test
    void collectForKafkaBuildsUserPartitionedEnvelope() throws Exception {
        LogCollector collector = new LogCollector(Clock.fixed(
                Instant.ofEpochMilli(1713503000000L), ZoneOffset.UTC), "user_events");

        LogCollector.KafkaEvent event = collector.collectForKafka(new LogCollector.UserBehaviorLog(
                "evt-rating", 123, 9, "rating", 0L, 0L, 5, 0L, "web", Map.of()));

        assertEquals("user_events", event.topic());
        assertEquals("user:123", event.key());
        assertEquals(LogCollector.SCHEMA_VERSION, event.headers().get("schemaVersion"));
        assertEquals("rating", event.headers().get("eventType"));
        assertEquals("rating", MAPPER.readTree(event.value()).get("eventType").asText());
    }

    @Test
    void collectAllowsSearchEventsWithoutMovieId() throws Exception {
        LogCollector collector = new LogCollector(Clock.fixed(
                Instant.ofEpochMilli(1713503000000L), ZoneOffset.UTC));

        String json = collector.collect(new LogCollector.UserBehaviorLog(
                "evt-search", 123, 0, "search", 0L, 0L, null, 0L, "web",
                Map.of("query", "space opera")));

        JsonNode node = MAPPER.readTree(json);
        assertEquals("search", node.get("eventType").asText());
        assertEquals(0, node.get("movieId").asInt());
        assertEquals("space opera", node.get("features").get("query").asText());
    }

    @Test
    void collectSanitizesFeatureMap() throws Exception {
        LogCollector collector = new LogCollector(Clock.fixed(
                Instant.ofEpochMilli(1713503000000L), ZoneOffset.UTC));
        Map<String, String> features = new HashMap<>();
        features.put(" rank ", " 4 ");
        features.put("requestId", " req-9 ");
        features.put(" ", "ignored");
        features.put("nullValue", null);

        String json = collector.collect(new LogCollector.UserBehaviorLog(
                "evt-1", 123, 9, "impression", 0L, null, 0L, "web", features));

        JsonNode featureNode = MAPPER.readTree(json).get("features");
        assertEquals("4", featureNode.get("rank").asText());
        assertEquals("req-9", featureNode.get("requestId").asText());
        assertFalse(featureNode.has("nullValue"));
    }
}
