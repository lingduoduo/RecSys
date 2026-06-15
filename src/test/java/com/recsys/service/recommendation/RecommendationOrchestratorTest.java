package com.recsys.service.recommendation;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.service.hydrator.RecommendationHydrator;
import com.recsys.service.pagination.CursorPaginationService;
import com.recsys.service.ranking.ScoreRanker;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.service.retrieval.RecallChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RecommendationOrchestratorTest {

    @Test
    void recommendRunsRecallRankHydrateAndCursorPagination() {
        RecallChannel recall = channel(
                new MovieCandidate("1", 0.10, "trending", Map.of()),
                new MovieCandidate("2", 0.90, "vector", Map.of()),
                new MovieCandidate("3", 0.70, "collab", Map.of())
        );
        RecommendationHydrator hydrator = (query, rankedMovies) -> rankedMovies.stream()
                .map(movie -> new RankedMovie(movie.itemId(), movie.score(), movie.rank(),
                        Map.of("hydrated", true)))
                .toList();
        RecommendationOrchestrator orchestrator = new RecommendationOrchestrator(
                new MultiChannelRecallService(List.of(recall)),
                new ScoreRanker(),
                hydrator,
                new CursorPaginationService(),
                3
        );

        RecommendationResult firstPage = orchestrator.recommend(
                new RecommendationQuery("u1", 2, Set.of(), null)
        );
        RecommendationResult secondPage = orchestrator.recommend(
                new RecommendationQuery("u1", 2, Set.of(), firstPage.nextCursor())
        );

        assertEquals(List.of("2", "3"), firstPage.items().stream().map(RankedMovie::itemId).toList());
        assertEquals(true, firstPage.items().get(0).features().get("hydrated"));
        assertNotNull(firstPage.nextCursor());
        assertEquals(List.of("1"), secondPage.items().stream().map(RankedMovie::itemId).toList());
    }

    private static RecallChannel channel(MovieCandidate... candidates) {
        return new RecallChannel() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                return List.of(candidates);
            }
        };
    }
}
