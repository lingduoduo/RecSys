package com.recsys.application.online;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.application.online.OnlineBlendingPipeline;
import com.recsys.application.online.OnlineRecommendationService;

import com.recsys.domain.item.Movie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.domain.user.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineBlendingPipelineTest {

    private final OnlineRecommendationService service = mock(OnlineRecommendationService.class);
    private final OnlineBlendingPipeline pipeline = new OnlineBlendingPipeline(service);

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
        assertThat(result.trace()).containsEntry("strategy", "online+model");
        assertThat(result.trace()).containsEntry("window", "last_hour");
    }

    @Test
    void nonNumericUserIdThrowsIllegalArgument() {
        assertThatThrownBy(() -> pipeline.recommend(
                new RecommendationQuery("not-a-number", 5, Set.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId must be numeric");
    }
}
