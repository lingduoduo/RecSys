package com.recsys.service.retrieval;

import com.recsys.infrastructure.DataManager;
import com.recsys.model.Movie;
import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PopularityChannelTest {

    @Test
    void recall_combinesTopRatedAndLatest() {
        DataManager dm = mock(DataManager.class);
        Movie a = new Movie(1, "Top Rated", 2020, List.of("Action"));
        Movie b = new Movie(2, "Latest", 2023, List.of("Drama"));
        when(dm.getTopRatedMovies(anyInt())).thenReturn(List.of(a));
        when(dm.getLatestMovies(anyInt())).thenReturn(List.of(b));

        PopularityChannel channel = new PopularityChannel(dm);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).extracting(MovieCandidate::itemId).containsExactlyInAnyOrder("1", "2");
        assertThat(results).extracting(MovieCandidate::score).containsOnly(0.4);
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("popularity");
    }

    @Test
    void recall_deduplicatesOverlappingTopRatedAndLatest() {
        DataManager dm = mock(DataManager.class);
        Movie m = new Movie(1, "Both", 2022, List.of("Comedy"));
        when(dm.getTopRatedMovies(anyInt())).thenReturn(List.of(m));
        when(dm.getLatestMovies(anyInt())).thenReturn(List.of(m)); // same movie in both

        List<MovieCandidate> results = new PopularityChannel(dm).recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).itemId()).isEqualTo("1");
    }

    @Test
    void name_returnsPopularity() {
        assertThat(new PopularityChannel(mock(DataManager.class)).name()).isEqualTo("popularity");
    }
}
