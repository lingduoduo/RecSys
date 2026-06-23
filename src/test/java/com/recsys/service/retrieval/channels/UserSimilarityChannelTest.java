package com.recsys.service.retrieval.channels;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.rating.Rating;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.DataManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserSimilarityChannelTest {

    @Test
    void recallScoresUnseenItemsFromMostSimilarUsers() {
        DataManager dataManager = mock(DataManager.class);
        when(dataManager.getAllRatings()).thenReturn(List.of(
                new Rating(1, 10, 5.0f, 1L),
                new Rating(1, 11, 3.0f, 2L),
                new Rating(2, 10, 5.0f, 3L),
                new Rating(2, 11, 3.0f, 4L),
                new Rating(2, 12, 4.0f, 5L),
                new Rating(3, 10, 1.0f, 6L),
                new Rating(3, 13, 5.0f, 7L),
                new Rating(4, 14, 5.0f, 8L)
        ));

        Channels.UserSimilarity channel = new Channels.UserSimilarity(dataManager);

        List<MovieCandidate> candidates = channel.recall(
                new RecommendationQuery("1", 10, Set.of("10", "11"), null), 10);

        assertThat(candidates).extracting(MovieCandidate::itemId)
                .containsExactly("12", "13");
        assertThat(candidates.get(0).score()).isGreaterThan(candidates.get(1).score());
        assertThat(candidates).extracting(MovieCandidate::channel)
                .containsOnly("user_similarity");
    }

    @Test
    void recallReturnsEmptyForUnknownOrInvalidUsers() {
        DataManager dataManager = mock(DataManager.class);
        when(dataManager.getAllRatings()).thenReturn(List.of(
                new Rating(2, 12, 4.0f, 1L)
        ));
        Channels.UserSimilarity channel = new Channels.UserSimilarity(dataManager);

        assertThat(channel.recall(new RecommendationQuery("1", 10, Set.of(), null), 10))
                .isEmpty();
        assertThat(channel.recall(new RecommendationQuery("alice", 10, Set.of(), null), 10))
                .isEmpty();
    }
}
