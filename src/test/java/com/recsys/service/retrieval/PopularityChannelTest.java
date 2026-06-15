package com.recsys.service.retrieval;

import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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

    @Test
    void recall_usesRedisWhenGlobalPopStoreNonEmpty() {
        DataManager dm = mock(DataManager.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);
        when(popStore.getTopIds(5)).thenReturn(List.of("10", "20", "30"));

        PopularityChannel channel = new PopularityChannel(dm, popStore);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        // rank-based: 1/(0+1)=1.0, 1/(1+1)=0.5, 1/(2+1)=0.333
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("10", "20", "30");
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).score()).isEqualTo(0.5);
        assertThat(results.get(2).score()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("popularity");
    }

    @Test
    void recall_fallsBackToDataManagerWhenRedisEmpty() {
        DataManager dm = mock(DataManager.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);
        when(popStore.getTopIds(anyInt())).thenReturn(List.of());
        Movie m = new Movie(1, "Top", 2020, List.of("Action"));
        when(dm.getTopRatedMovies(anyInt())).thenReturn(List.of(m));
        when(dm.getLatestMovies(anyInt())).thenReturn(List.of());

        PopularityChannel channel = new PopularityChannel(dm, popStore);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).itemId()).isEqualTo("1");
        assertThat(results.get(0).score()).isEqualTo(0.4); // DataManager fallback uses flat score
    }

    @Test
    void recall_oneArgConstructorStillUsesDataManager() {
        DataManager dm = mock(DataManager.class);
        Movie m = new Movie(99, "Classic", 2019, List.of("Drama"));
        when(dm.getTopRatedMovies(anyInt())).thenReturn(List.of(m));
        when(dm.getLatestMovies(anyInt())).thenReturn(List.of());

        List<MovieCandidate> results = new PopularityChannel(dm).recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(0.4);
    }
}
