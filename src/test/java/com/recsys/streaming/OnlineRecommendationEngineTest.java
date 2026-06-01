package com.recsys.streaming;

import com.recsys.infrastructure.DataManager;
import com.recsys.model.Movie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineRecommendationEngineTest {

    @Test
    void blendsRecentHistoryWithTrendingAndExcludesRecentlyWatchedMovies() {
        DataManager dataManager = DataManager.getInstance();
        TrendingStore topkStore = mock(TrendingStore.class);
        RecentHistoryStore featureStore = mock(RecentHistoryStore.class);

        when(featureStore.getRecentMovieIds(123, 3)).thenReturn(List.of(1, 2, 4));
        when(topkStore.getTopKIds("last_hour", 12)).thenReturn(List.of("11", "1", "2", "3", "4", "12"));

        OnlineRecommendationEngine engine = new OnlineRecommendationEngine(dataManager, topkStore, featureStore);
        OnlineRecommendationEngine.OnlineRecommendationResult result = engine.recommend(123, "last_hour", 3);

        assertFalse(result.recommendations().isEmpty());
        assertFalse(result.recommendations().stream().map(Movie::id).anyMatch(id -> id == 1 || id == 2 || id == 4));
    }

    @Test
    void rejectsUnknownWindows() {
        DataManager dataManager = DataManager.getInstance();
        TrendingStore topkStore = mock(TrendingStore.class);
        RecentHistoryStore featureStore = mock(RecentHistoryStore.class);

        OnlineRecommendationEngine engine = new OnlineRecommendationEngine(dataManager, topkStore, featureStore);

        assertThrows(IllegalArgumentException.class, () -> engine.recommend(123, "last_week", 5));
    }
}
