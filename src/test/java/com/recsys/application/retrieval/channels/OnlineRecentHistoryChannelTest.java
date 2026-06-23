package com.recsys.application.retrieval.channels;

import com.recsys.domain.item.Movie;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.infrastructure.store.RecentHistoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineRecentHistoryChannelTest {

    private static Movie movie(int id) { return new Movie(id, "M" + id, 2020, List.of("Drama")); }

    private static RecommendationQuery query(String userId) {
        return new RecommendationQuery(userId, 10, Set.of(), null);
    }

    @Test
    void blendsSimilarMoviesAcrossRecentSeeds_rankBasedScores() {
        RecentHistoryStore recent = mock(RecentHistoryStore.class);
        DataManager dm = mock(DataManager.class);
        when(recent.getRecentMovieIds(eq(7), eq(3))).thenReturn(List.of(10, 20));
        when(dm.getSimilarMovies(10)).thenReturn(List.of(movie(1), movie(2), movie(3)));
        when(dm.getSimilarMovies(20)).thenReturn(List.of(movie(2), movie(4)));

        Channels.OnlineRecentHistory channel = new Channels.OnlineRecentHistory(recent, dm);
        List<MovieCandidate> out = channel.recall(query("7"), 10);

        assertThat(out).isNotEmpty();
        assertThat(out).extracting(MovieCandidate::channel).containsOnly("online_recent_history");
        // movie 2 appears under both seeds -> highest blended order -> rank-0 -> score 1.0
        assertThat(out.get(0).itemId()).isEqualTo("2");
        assertThat(out.get(0).score()).isEqualTo(1.0);
        // scores are strictly rank-based and descending
        assertThat(out.get(1).score()).isEqualTo(0.5);
    }

    @Test
    void emptyWhenNoRecentHistory() {
        RecentHistoryStore recent = mock(RecentHistoryStore.class);
        DataManager dm = mock(DataManager.class);
        when(recent.getRecentMovieIds(eq(7), eq(3))).thenReturn(List.of());

        Channels.OnlineRecentHistory channel = new Channels.OnlineRecentHistory(recent, dm);
        assertThat(channel.recall(query("7"), 10)).isEmpty();
    }

    @Test
    void emptyWhenUserIdNotNumeric() {
        Channels.OnlineRecentHistory channel =
                new Channels.OnlineRecentHistory(mock(RecentHistoryStore.class), mock(DataManager.class));
        assertThat(channel.recall(query("user_7"), 10)).isEmpty();
    }

    @Test
    void respectsLimit() {
        RecentHistoryStore recent = mock(RecentHistoryStore.class);
        DataManager dm = mock(DataManager.class);
        when(recent.getRecentMovieIds(eq(7), eq(3))).thenReturn(List.of(10));
        when(dm.getSimilarMovies(10)).thenReturn(List.of(movie(1), movie(2), movie(3), movie(4)));

        Channels.OnlineRecentHistory channel = new Channels.OnlineRecentHistory(recent, dm);
        assertThat(channel.recall(query("7"), 2)).hasSize(2);
    }
}
