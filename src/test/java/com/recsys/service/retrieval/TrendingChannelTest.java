package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.streaming.TrendingStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrendingChannelTest {

    @Test
    void recall_mapsTopKIdsToMovieCandidates() {
        TrendingStore store = mock(TrendingStore.class);
        when(store.getTopKIds("last_hour", 5)).thenReturn(List.of("10", "20", "30"));

        TrendingChannel channel = new TrendingChannel(store);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).hasSize(3);
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("10", "20", "30");
        assertThat(results).extracting(MovieCandidate::score).containsOnly(0.6);
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("trending");
    }

    @Test
    void recall_emptyWhenStoreReturnsEmpty() {
        TrendingStore store = mock(TrendingStore.class);
        when(store.getTopKIds("last_hour", 5)).thenReturn(List.of());

        List<MovieCandidate> results = new TrendingChannel(store).recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }

    @Test
    void name_returnsTrending() {
        assertThat(new TrendingChannel(mock(TrendingStore.class)).name()).isEqualTo("trending");
    }
}
