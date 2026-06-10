package com.recsys.online.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.online.learner.OnlineJoiner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceCollectorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void collectGroupsJoinedSamplesIntoRankedRecommendationExperience() throws Exception {
        OnlineJoiner joiner = new OnlineJoiner();
        OnlineJoiner.JoinedSample second = sample(joiner, "evt-2", 11, "impression", 2, 0);
        OnlineJoiner.JoinedSample first = sample(joiner, "evt-1", 9, "click", 1, 1);
        OnlineJoiner.JoinedSample otherRequest = joiner.join(new LogCollector.UserBehaviorLog(
                        "evt-3", 123, 12, "like", 0L, null, 103L, "web",
                        Map.of("requestId", "req-10", "rank", "1")),
                Map.of(), Map.of("genre", "Action"), Map.of());

        List<ExperienceCollector.RecommendationExperience> experiences =
                new ExperienceCollector().collect(List.of(second, first, otherRequest));

        assertEquals(2, experiences.size());
        ExperienceCollector.RecommendationExperience experience = experiences.get(0);
        assertEquals("req-9", experience.requestId());
        assertEquals(123, experience.userId());
        assertEquals(101L, experience.eventTimeMillis());
        assertEquals(1, experience.label());
        assertEquals(List.of(9, 11), experience.items().stream()
                .map(ExperienceCollector.ItemFeedback::movieId)
                .toList());
        assertEquals(1, experience.items().get(0).rank());
        assertEquals(2, experience.items().get(1).rank());

        JsonNode json = MAPPER.readTree(new ExperienceCollector().toJson(experience));
        assertEquals("req-9", json.get("requestId").asText());
        assertEquals(2, json.get("items").size());
        assertEquals(9, json.get("items").get(0).get("movieId").asInt());
    }

    @Test
    void collectFallsBackToEventIdWhenRequestIdIsMissing() {
        OnlineJoiner.JoinedSample sample = new OnlineJoiner().join(
                new LogCollector.UserBehaviorLog("evt-1", 123, 9, "impression", 0L, null, 100L, "web", Map.of()),
                Map.of(), Map.of(), Map.of());

        List<ExperienceCollector.RecommendationExperience> experiences =
                new ExperienceCollector().collect(List.of(sample));

        assertEquals("event:evt-1", experiences.get(0).requestId());
        assertEquals(0, experiences.get(0).label());
        assertTrue(experiences.get(0).items().get(0).features().containsKey("event.source"));
    }

    @Test
    void collectCompactsDuplicateMovieFeedbackWithinSameRequest() {
        OnlineJoiner joiner = new OnlineJoiner();
        OnlineJoiner.JoinedSample impression = joiner.join(new LogCollector.UserBehaviorLog(
                        "evt-1", 123, 9, "impression", 0L, null, 100L, "web",
                        Map.of("requestId", "req-9", "rank", "2")),
                Map.of(), Map.of(), Map.of());
        OnlineJoiner.JoinedSample click = joiner.join(new LogCollector.UserBehaviorLog(
                        "evt-2", 123, 9, "click", 0L, null, 101L, "web",
                        Map.of("requestId", "req-9", "rank", "2")),
                Map.of(), Map.of(), Map.of());

        ExperienceCollector.RecommendationExperience experience =
                new ExperienceCollector().collect(List.of(impression, click)).get(0);

        assertEquals(1, experience.items().size());
        assertEquals(1, experience.label());
        assertEquals(1, experience.items().get(0).label());
        assertEquals("click", experience.items().get(0).eventType());
    }

    @Test
    void collectKeepsSameRequestIdsSeparateAcrossUsers() {
        OnlineJoiner joiner = new OnlineJoiner();
        OnlineJoiner.JoinedSample user123 = joiner.join(new LogCollector.UserBehaviorLog(
                        "evt-1", 123, 9, "click", 0L, null, 100L, "web",
                        Map.of("requestId", "req-9", "rank", "1")),
                Map.of(), Map.of(), Map.of());
        OnlineJoiner.JoinedSample user124 = joiner.join(new LogCollector.UserBehaviorLog(
                        "evt-2", 124, 11, "click", 0L, null, 101L, "web",
                        Map.of("requestId", "req-9", "rank", "1")),
                Map.of(), Map.of(), Map.of());

        List<ExperienceCollector.RecommendationExperience> experiences =
                new ExperienceCollector().collect(List.of(user123, user124));

        assertEquals(2, experiences.size());
        assertEquals(List.of(123, 124), experiences.stream()
                .map(ExperienceCollector.RecommendationExperience::userId)
                .toList());
        assertEquals(List.of("req-9", "req-9"), experiences.stream()
                .map(ExperienceCollector.RecommendationExperience::requestId)
                .toList());
    }

    private static OnlineJoiner.JoinedSample sample(OnlineJoiner joiner,
                                                    String eventId,
                                                    int movieId,
                                                    String eventType,
                                                    int rank,
                                                    int expectedLabel) {
        OnlineJoiner.JoinedSample sample = joiner.join(new LogCollector.UserBehaviorLog(
                        eventId, 123, movieId, eventType, 0L, null, 100L + rank, "mobile",
                        Map.of("requestId", "req-9", "rank", Integer.toString(rank))),
                Map.of("ageBucket", "25-34"),
                Map.of("genre", "Drama"),
                Map.of("surface", "home"));
        assertEquals(expectedLabel, sample.label());
        return sample;
    }
}
