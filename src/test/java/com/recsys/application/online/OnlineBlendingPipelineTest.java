package com.recsys.application.online;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.application.pagination.CursorPaginationService;
import com.recsys.application.pagination.RecommendationCursorCodec;
import com.recsys.application.pagination.RecommendationPaginationConfig;
import com.recsys.application.pagination.RecommendationPaginationCoordinator;
import com.recsys.application.pagination.RecommendationPaginationMetrics;
import com.recsys.domain.item.Movie;
import com.recsys.domain.online.OnlineRecommendationRequest;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.domain.user.User;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnlineBlendingPipelineTest {

    private static final int MAX_CANDIDATES = 500;
    private final OnlineRecommendationService service = mock(OnlineRecommendationService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OnlineBlendingPipeline pipeline =
            new OnlineBlendingPipeline(service, pagination(registry, MAX_CANDIDATES), MAX_CANDIDATES);

    @Test
    void convertsMoviesToRankedMoviesWithPositionScores() {
        User user = new User(1, "Alice");
        List<Movie> recs = List.of(
                new Movie(10, "A", 2020, List.of()),
                new Movie(20, "B", 2021, List.of()));
        when(service.recommend(any())).thenReturn(
                new OnlineRecommendationResult(user, "last_hour", "online+model",
                        List.of(), List.of(), recs));

        RecommendationResult result = pipeline.recommend(
                new RecommendationQuery("1", 5, Set.of(), null));

        assertThat(result.userId()).isEqualTo("1");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).itemId()).isEqualTo("10");
        assertThat(result.items().get(0).rank()).isEqualTo(1);
        assertThat(result.items().get(0).score()).isGreaterThan(result.items().get(1).score());
        assertThat(result.items().get(1).itemId()).isEqualTo("20");
        assertThat(result.items().get(1).rank()).isEqualTo(2);
        assertThat(result.nextCursor()).isNull();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.trace()).containsEntry("strategy", "online+model");
        assertThat(result.trace()).containsEntry("window", "last_hour");
    }

    @Test
    void honorsExclusionsAndContinuesWithSignedCursor() {
        User user = new User(7, "Alice");
        when(service.recommend(any())).thenReturn(
                new OnlineRecommendationResult(user, "last_day", "online+model",
                        List.of(), List.of(), List.of(
                                movie(1), movie(2), movie(3), movie(4))));

        RecommendationResult first = pipeline.recommend(
                new RecommendationQuery("7", 2, Set.of("1"), null));
        RecommendationResult second = pipeline.recommend(
                new RecommendationQuery("7", 2, Set.of("1"), first.nextCursor()));

        assertThat(first.items()).extracting("itemId").containsExactly("2", "3");
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).extracting("itemId").containsExactly("4");
        assertThat(second.nextCursor()).isNull();
        assertThat(second.hasMore()).isFalse();
        assertThat(second.trace())
                .containsEntry("strategy", "online+model")
                .containsEntry("window", "last_day");

        ArgumentCaptor<OnlineRecommendationRequest> request =
                ArgumentCaptor.forClass(OnlineRecommendationRequest.class);
        verify(service, org.mockito.Mockito.times(2)).recommend(request.capture());
        assertThat(request.getAllValues())
                .extracting(OnlineRecommendationRequest::k)
                .containsOnly(MAX_CANDIDATES);
    }

    @Test
    void invalidCursorIsRejectedBeforeRecommendationSourceRuns() {
        RecommendationQuery query =
                new RecommendationQuery("7", 2, Set.of("1"), "not-a-valid-cursor");

        assertThatThrownBy(() -> pipeline.recommend(query))
                .isInstanceOf(RecommendationCursorCodec.InvalidCursorException.class);

        verify(service, never()).recommend(any());
    }

    @Test
    void reportsBudgetExhaustionFromRawWindowSizeBeforeExclusions() {
        int windowSize = 101;
        List<Movie> rawWindow = IntStream.rangeClosed(1, windowSize)
                .mapToObj(OnlineBlendingPipelineTest::movie)
                .toList();
        Set<String> exclusions = IntStream.range(1, windowSize)
                .mapToObj(String::valueOf)
                .collect(Collectors.toSet());
        when(service.recommend(any())).thenReturn(
                new OnlineRecommendationResult(
                        new User(7, "Alice"), "last_hour", "online",
                        List.of(), List.of(), rawWindow));
        SimpleMeterRegistry focusedRegistry = new SimpleMeterRegistry();
        OnlineBlendingPipeline focusedPipeline = new OnlineBlendingPipeline(
                service, pagination(focusedRegistry, windowSize), windowSize);

        RecommendationResult result = focusedPipeline.recommend(
                new RecommendationQuery("7", 1, exclusions, null));

        assertThat(result.items()).extracting("itemId").containsExactly("101");
        assertThat(result.hasMore()).isFalse();
        assertThat(focusedRegistry.get("recsys.pagination.budget.exhausted")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void nonNumericUserIdThrowsIllegalArgument() {
        assertThatThrownBy(() -> pipeline.recommend(
                new RecommendationQuery("not-a-number", 5, Set.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId must be numeric");
    }

    private static Movie movie(int id) {
        return new Movie(id, "M" + id, 2020, List.of());
    }

    private static RecommendationPaginationCoordinator pagination(
            SimpleMeterRegistry registry,
            int maxCandidates
    ) {
        RecommendationPaginationConfig config = new RecommendationPaginationConfig(
                "online-pagination-test-signing-key".repeat(2),
                null,
                Duration.ofMinutes(15),
                false,
                maxCandidates);
        return new RecommendationPaginationCoordinator(
                new RecommendationCursorCodec(config, Clock.systemUTC()),
                new CursorPaginationService(),
                new RecommendationPaginationMetrics(registry));
    }
}
