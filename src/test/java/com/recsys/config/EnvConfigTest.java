package com.recsys.config;

import com.recsys.application.pagination.RecommendationPaginationRuntime;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvConfigTest {

    // Reliably testable without mutating process env: an absent variable always
    // falls back to the supplied default. Uses an env-var name that will not exist.
    private static final String ABSENT = "RECSYS_ENVCONFIG_TEST_ABSENT_VAR";

    @Test
    void readInt_returnsDefaultWhenAbsent() {
        assertThat(EnvConfig.readInt(ABSENT, 7)).isEqualTo(7);
    }

    @Test
    void readLong_returnsDefaultWhenAbsent() {
        assertThat(EnvConfig.readLong(ABSENT, 9_999_999_999L)).isEqualTo(9_999_999_999L);
    }

    @Test
    void readDouble_returnsDefaultWhenAbsent() {
        assertThat(EnvConfig.readDouble(ABSENT, 0.75)).isEqualTo(0.75);
    }

    @Test
    void paginationRuntimeRejectsMissingSigningKeyBeforeServerConstruction() {
        assertThatThrownBy(() -> RecommendationPaginationRuntime.fromEnvironment(
                name -> null, new SimpleMeterRegistry(), fixedClock()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RECOMMENDATION_CURSOR_SIGNING_KEY");
    }

    @Test
    void paginationRuntimesAcceptCursorsSignedByThePreviousKey() {
        String oldKey = "o".repeat(32);
        Map<String, String> oldEnvironment = Map.of(
                "RECOMMENDATION_CURSOR_SIGNING_KEY", oldKey);
        Map<String, String> rotatedEnvironment = Map.of(
                "RECOMMENDATION_CURSOR_SIGNING_KEY", "n".repeat(32),
                "RECOMMENDATION_CURSOR_PREVIOUS_KEY", oldKey);
        RecommendationPaginationRuntime oldRuntime =
                RecommendationPaginationRuntime.fromEnvironment(
                        oldEnvironment::get, new SimpleMeterRegistry(), fixedClock());
        RecommendationPaginationRuntime rotatedRuntime =
                RecommendationPaginationRuntime.fromEnvironment(
                        rotatedEnvironment::get, new SimpleMeterRegistry(), fixedClock());
        RecommendationQuery firstQuery =
                new RecommendationQuery("u1", 2, Set.of(), null);
        var firstPage = oldRuntime.coordinator().page(firstQuery, rankedMovies(), false);

        var secondPage = rotatedRuntime.coordinator().page(
                new RecommendationQuery("u1", 2, Set.of(), firstPage.nextCursor()),
                rankedMovies(),
                false);

        assertThat(secondPage.items())
                .extracting(RankedMovie::itemId)
                .containsExactly("c");
        assertThat(secondPage.hasMore()).isFalse();
    }

    @Test
    void servingPathsConstructCompatiblePaginationCoordinators() {
        Map<String, String> environment = Map.of(
                "RECOMMENDATION_CURSOR_SIGNING_KEY", "k".repeat(32),
                "RECOMMENDATION_PAGINATION_MAX_CANDIDATES", "777");
        RecommendationPaginationRuntime catalogRuntime =
                RecommendationPaginationRuntime.fromEnvironment(
                        environment::get, new SimpleMeterRegistry(), fixedClock());
        RecommendationPaginationRuntime onlineRuntime =
                RecommendationPaginationRuntime.fromEnvironment(
                        environment::get, new SimpleMeterRegistry(), fixedClock());
        RecommendationQuery firstQuery =
                new RecommendationQuery("u1", 2, Set.of(), null);
        var firstPage =
                catalogRuntime.coordinator().page(firstQuery, rankedMovies(), false);

        var secondPage = onlineRuntime.coordinator().page(
                new RecommendationQuery("u1", 2, Set.of(), firstPage.nextCursor()),
                rankedMovies(),
                false);

        assertThat(catalogRuntime.maxCandidates()).isEqualTo(777);
        assertThat(onlineRuntime.maxCandidates()).isEqualTo(777);
        assertThat(firstPage.items())
                .extracting(RankedMovie::itemId)
                .containsExactly("a", "b");
        assertThat(secondPage.items())
                .extracting(RankedMovie::itemId)
                .containsExactly("c");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC);
    }

    private static List<RankedMovie> rankedMovies() {
        return List.of(
                new RankedMovie("a", 0.9, 1, Map.of()),
                new RankedMovie("b", 0.8, 2, Map.of()),
                new RankedMovie("c", 0.7, 3, Map.of()));
    }
}
