package com.recsys.model.service;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.online.store.RecentHistoryStore;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRetrievalStageTest {

    private final MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
    private final RecentHistoryStore history = mock(RecentHistoryStore.class);
    private final ModelRetrievalStage stage = new ModelRetrievalStage(recall, history);

    @Test
    void retrieve_delegates_whenNoRecentHistory() {
        when(history.getRecentMovieIds(anyInt(), anyInt())).thenReturn(List.of());
        RecommendationQuery query = new RecommendationQuery("123", 50, Set.of("1"), null);
        List<MovieCandidate> expected = List.of(new MovieCandidate("4", 0.9, "trending", Map.of()));
        when(recall.recall(query, 50)).thenReturn(expected);

        assertThat(stage.retrieve(query, 50)).isEqualTo(expected);

        ArgumentCaptor<RecommendationQuery> captor = ArgumentCaptor.forClass(RecommendationQuery.class);
        verify(recall).recall(captor.capture(), eq(50));
        assertThat(captor.getValue().excludedItemIds()).containsExactly("1");  // unchanged, no augmentation
    }

    @Test
    void retrieve_unionsRecentWatchesIntoExclusions() {
        when(history.getRecentMovieIds(eq(123), anyInt())).thenReturn(List.of(7, 8));
        RecommendationQuery query = new RecommendationQuery("123", 50, Set.of("1"), null);

        stage.retrieve(query, 50);

        ArgumentCaptor<RecommendationQuery> captor = ArgumentCaptor.forClass(RecommendationQuery.class);
        verify(recall).recall(captor.capture(), eq(50));
        assertThat(captor.getValue().excludedItemIds()).containsExactlyInAnyOrder("1", "7", "8");
        assertThat(captor.getValue().userId()).isEqualTo("123");
        assertThat(captor.getValue().limit()).isEqualTo(50);
    }

    @Test
    void retrieve_nonNumericUserId_skipsAugmentation() {
        RecommendationQuery query = new RecommendationQuery("alice", 50, Set.of("1"), null);

        stage.retrieve(query, 50);

        ArgumentCaptor<RecommendationQuery> captor = ArgumentCaptor.forClass(RecommendationQuery.class);
        verify(recall).recall(captor.capture(), eq(50));
        assertThat(captor.getValue().excludedItemIds()).containsExactly("1");
    }

    @Test
    void retrieve_storeError_skipsAugmentationGracefully() {
        when(history.getRecentMovieIds(anyInt(), anyInt())).thenThrow(new RuntimeException("redis down"));
        RecommendationQuery query = new RecommendationQuery("123", 50, Set.of("1"), null);

        stage.retrieve(query, 50);   // must not throw

        ArgumentCaptor<RecommendationQuery> captor = ArgumentCaptor.forClass(RecommendationQuery.class);
        verify(recall).recall(captor.capture(), eq(50));
        assertThat(captor.getValue().excludedItemIds()).containsExactly("1");
    }
}
