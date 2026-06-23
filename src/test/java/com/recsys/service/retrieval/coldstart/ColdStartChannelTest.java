package com.recsys.service.retrieval.coldstart;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.infrastructure.store.TrendingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ColdStartChannelTest {

    @Test
    void name_returnsColdStart() {
        assertThat(new ColdStartChannel(mock(TrendingStore.class), mock(GlobalPopularityStore.class))
                .name()).isEqualTo("cold_start");
    }

    @Test
    void recall_blendsSourcesByWeightedRankScore() {
        TrendingStore store = mock(TrendingStore.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);

        // last_day weight 0.7: "A" rank0 → 0.7, "B" rank1 → 0.35
        when(store.getTopKIds("last_day", 5)).thenReturn(List.of("A", "B"));
        // last_month weight 0.5: "B" rank0 → 0.5, "C" rank1 → 0.25
        when(store.getTopKIds("last_month", 5)).thenReturn(List.of("B", "C"));
        // global pop weight 0.4: "A" rank0 → 0.4
        when(popStore.getTopIds(5)).thenReturn(List.of("A"));

        ColdStartChannel channel = new ColdStartChannel(store, popStore);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("999", 5, Set.of(), null), 5);

        // "A": 0.7 + 0.4 = 1.1  |  "B": 0.35 + 0.5 = 0.85  |  "C": 0.25
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("A", "B", "C");
        assertThat(results.get(0).score()).isCloseTo(1.1, within(1e-9));
        assertThat(results.get(1).score()).isCloseTo(0.85, within(1e-9));
        assertThat(results.get(2).score()).isCloseTo(0.25, within(1e-9));
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("cold_start");
    }

    @Test
    void recall_filtersExcludedItems() {
        TrendingStore store = mock(TrendingStore.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);
        when(store.getTopKIds("last_day", 5)).thenReturn(List.of("A", "B"));
        when(store.getTopKIds("last_month", 5)).thenReturn(List.of());
        when(popStore.getTopIds(5)).thenReturn(List.of());

        ColdStartChannel channel = new ColdStartChannel(store, popStore);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("999", 5, Set.of("A"), null), 5);

        assertThat(results).extracting(MovieCandidate::itemId).containsOnly("B");
    }

    @Test
    void recall_emptyWhenAllSourcesEmpty() {
        TrendingStore store = mock(TrendingStore.class);
        GlobalPopularityStore popStore = mock(GlobalPopularityStore.class);
        when(store.getTopKIds("last_day", 5)).thenReturn(List.of());
        when(store.getTopKIds("last_month", 5)).thenReturn(List.of());
        when(popStore.getTopIds(5)).thenReturn(List.of());

        List<MovieCandidate> results = new ColdStartChannel(store, popStore).recall(
                new RecommendationQuery("999", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }
}
