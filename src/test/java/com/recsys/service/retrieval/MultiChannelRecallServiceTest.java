package com.recsys.service.retrieval;

import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiChannelRecallServiceTest {

    @Test
    void recallMergesDuplicatesByBestScoreAndSkipsExcludedItems() {
        RecallChannel vector = channel("vector",
                new MovieCandidate("1", 0.90, "vector", Map.of()),
                new MovieCandidate("2", 0.40, "vector", Map.of()));
        RecallChannel trending = channel("trending",
                new MovieCandidate("1", 0.50, "trending", Map.of()),
                new MovieCandidate("3", 0.70, "trending", Map.of()));
        MultiChannelRecallService service = new MultiChannelRecallService(List.of(vector, trending));

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("u1", 10, Set.of("2"), null),
                10
        );

        assertEquals(List.of("1", "3"), recalled.stream().map(MovieCandidate::itemId).toList());
        assertEquals("vector", recalled.get(0).channel());
    }

    private static RecallChannel channel(String name, MovieCandidate... candidates) {
        return new RecallChannel() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                return List.of(candidates);
            }
        };
    }
}
