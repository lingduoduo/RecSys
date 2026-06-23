package com.recsys.service.retrieval.channels;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.online.store.TrendingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrendingChannelTest {

    @Test
    void name_returnsTrending() {
        assertThat(new Channels.Trending(mock(TrendingStore.class)).name()).isEqualTo("trending");
    }

    @Test
    void recall_singleWindowUsesRankBasedScore() {
        TrendingStore store = mock(TrendingStore.class);
        when(store.getTopKIds("last_hour", 3)).thenReturn(List.of("10", "20", "30"));

        Channels.Trending channel = new Channels.Trending(store);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 3, Set.of(), null), 3);

        assertThat(results).hasSize(3);
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("10", "20", "30");
        // last_hour weight 1.0: rank0 → 1.0, rank1 → 0.5, rank2 → 0.333
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).score()).isEqualTo(0.5);
        assertThat(results.get(2).score()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("trending");
    }

    @Test
    void recall_multiWindowBlendsSumsByWeightedRank() {
        TrendingStore store = mock(TrendingStore.class);
        // last_hour weight 1.0: "10" rank0 → 1.0, "20" rank1 → 0.5
        when(store.getTopKIds("last_hour", 5)).thenReturn(List.of("10", "20"));
        // last_day weight 0.6: "10" rank0 → 0.6, "30" rank1 → 0.3
        when(store.getTopKIds("last_day", 5)).thenReturn(List.of("10", "30"));

        Channels.Trending channel = new Channels.Trending(store, List.of("last_hour", "last_day"));
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        // "10": 1.0 + 0.6 = 1.6  |  "20": 0.5  |  "30": 0.3
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("10", "20", "30");
        assertThat(results.get(0).score()).isCloseTo(1.6, within(1e-9));
        assertThat(results.get(1).score()).isCloseTo(0.5, within(1e-9));
        assertThat(results.get(2).score()).isCloseTo(0.3, within(1e-9));
    }

    @Test
    void recall_emptyWhenStoreReturnsEmpty() {
        TrendingStore store = mock(TrendingStore.class);
        when(store.getTopKIds("last_hour", 5)).thenReturn(List.of());

        List<MovieCandidate> results = new Channels.Trending(store).recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }
}
