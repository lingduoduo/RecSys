package com.recsys.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineJoinerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void joinJsonBuildsLabeledSampleWithNamespacedFeatures() throws Exception {
        LogCollector collector = new LogCollector(Clock.fixed(
                Instant.ofEpochMilli(1713503000000L), ZoneOffset.UTC));
        String behaviorLogJson = collector.collect(new LogCollector.UserBehaviorLog(
                "evt-1",
                123,
                9,
                "click",
                0L,
                null,
                0L,
                "mobile",
                Map.of("rank", "4", "requestId", "req-9")
        ));

        OnlineJoiner.JoinedSample sample = new OnlineJoiner().joinJson(
                behaviorLogJson,
                Map.of("ageBucket", "25-34"),
                Map.of("genre", "Drama"),
                Map.of("abTestVariant", "training")
        );

        assertEquals("evt-1", sample.eventId());
        assertEquals(123, sample.userId());
        assertEquals(9, sample.movieId());
        assertEquals("click", sample.eventType());
        assertEquals(1, sample.label());
        assertEquals("25-34", sample.features().get("user.ageBucket"));
        assertEquals("Drama", sample.features().get("item.genre"));
        assertEquals("training", sample.features().get("context.abTestVariant"));
        assertEquals("4", sample.features().get("event.rank"));
        assertEquals("mobile", sample.features().get("event.source"));

        JsonNode json = MAPPER.readTree(new OnlineJoiner().toJson(sample));
        assertEquals(1, json.get("label").asInt());
        assertEquals("req-9", json.get("features").get("event.requestId").asText());
    }

    @Test
    void joinAssignsStrongerLabelsForOrderAndLikeFeedback() {
        OnlineJoiner joiner = new OnlineJoiner();

        OnlineJoiner.JoinedSample like = joiner.join(new LogCollector.UserBehaviorLog(
                "evt-like", 123, 9, "like", 0L, null, 100L, "web", Map.of()),
                Map.of(), Map.of(), Map.of());
        OnlineJoiner.JoinedSample order = joiner.join(new LogCollector.UserBehaviorLog(
                "evt-order", 123, 9, "order", 0L, null, 100L, "web", Map.of()),
                Map.of(), Map.of(), Map.of());
        OnlineJoiner.JoinedSample impression = joiner.join(new LogCollector.UserBehaviorLog(
                "evt-imp", 123, 9, "impression", 0L, null, 100L, "web", Map.of()),
                Map.of(), Map.of(), Map.of());

        assertEquals(2, like.label());
        assertEquals(3, order.label());
        assertEquals(0, impression.label());
    }

    @Test
    void joinRejectsInvalidLogIdentity() {
        OnlineJoiner joiner = new OnlineJoiner();

        assertThrows(IllegalArgumentException.class, () -> joiner.join(
                new LogCollector.UserBehaviorLog("evt-1", 0, 9, "click", 0L, null, 100L, "web", Map.of()),
                Map.of(), Map.of(), Map.of()));
    }

    @Test
    void joinSanitizesAndNamespacesFeatureMapsDeterministically() {
        Map<String, String> userFeatures = new HashMap<>();
        userFeatures.put(" ageBucket ", " 25-34 ");
        userFeatures.put("ignored", null);
        Map<String, String> itemFeatures = new HashMap<>();
        itemFeatures.put(" genre ", " Drama ");
        itemFeatures.put(" ", "ignored");

        OnlineJoiner.JoinedSample sample = new OnlineJoiner().join(new LogCollector.UserBehaviorLog(
                        "evt-1", 123, 9, "click", 0L, null, 100L, " mobile ",
                        Map.of(" requestId ", " req-9 ", " rank ", " 4 ")),
                userFeatures,
                itemFeatures,
                Map.of(" surface ", " home "));

        assertEquals("25-34", sample.features().get("user.ageBucket"));
        assertEquals("Drama", sample.features().get("item.genre"));
        assertEquals("home", sample.features().get("context.surface"));
        assertEquals("req-9", sample.features().get("event.requestId"));
        assertEquals("4", sample.features().get("event.rank"));
        assertTrue(sample.features().keySet().stream().noneMatch(key -> key.contains(" ")));
        assertThrows(UnsupportedOperationException.class, () -> sample.features().put("x", "y"));
    }
}
