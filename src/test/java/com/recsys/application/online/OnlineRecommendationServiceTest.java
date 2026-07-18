package com.recsys.application.online;
import com.recsys.domain.online.OnlineRecommendationRequest;
import com.recsys.domain.online.OnlineRecommendationResult;
import com.recsys.application.online.OnlineRecommendationService;

import com.recsys.domain.item.Movie;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.user.User;
import com.recsys.infrastructure.dataloading.DataManager;
import com.recsys.infrastructure.messaging.ExperienceCollector;
import com.recsys.application.online.OnlineLearner;
import com.recsys.infrastructure.store.RecentHistoryStore;
import com.recsys.infrastructure.store.TrendingStore;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class OnlineRecommendationServiceTest {

    private static final User USER = new User(123, "Alice");

    private DataManager dataManager;
    private MultiChannelRecallService recallService;
    private RecentHistoryStore recentHistoryStore;
    private TrendingStore topkStore;
    private OnlineLearner onlineLearner;
    private OnlineRecommendationService service;

    private static Movie movie(int id) { return new Movie(id, "M" + id, 2020, List.of("Drama")); }
    private static MovieCandidate cand(int id, double score) {
        return new MovieCandidate(String.valueOf(id), score, "embedding", Map.of());
    }

    @BeforeEach
    void setUp() {
        dataManager = mock(DataManager.class);
        recallService = mock(MultiChannelRecallService.class);
        recentHistoryStore = mock(RecentHistoryStore.class);
        topkStore = mock(TrendingStore.class);
        onlineLearner = new OnlineLearner();
        service = new OnlineRecommendationService(
                dataManager, recallService, recentHistoryStore, topkStore, onlineLearner);

        when(dataManager.getUserById(USER.userId())).thenReturn(USER);
        when(dataManager.getUserById(999)).thenReturn(null);
        // identity movie lookups for ids used below
        for (int id : new int[]{3, 4, 5, 6}) when(dataManager.getMovieById(id)).thenReturn(movie(id));
        when(recentHistoryStore.getRecentMovieIds(eq(USER.userId()), anyInt())).thenReturn(List.of());
        when(topkStore.getTopKIds(eq("last_hour"), anyInt())).thenReturn(List.of());
    }

    @Test
    void recommendationsComeFromRecallServiceInScoreOrder() {
        when(recallService.recall(any(RecommendationQuery.class), anyInt()))
                .thenReturn(List.of(cand(5, 0.9), cand(4, 0.7), cand(3, 0.5)));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 3));

        assertEquals("multichannel", result.strategy());
        assertEquals(List.of(5, 4, 3),
                result.recommendations().stream().map(Movie::id).toList());
    }

    @Test
    void recallLimitHasHeadroomForSmallK() {
        when(recallService.recall(any(RecommendationQuery.class), anyInt())).thenReturn(List.of());
        service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 1));
        // recallLimit = max(k*4, 12) = 12
        verify(recallService).recall(any(RecommendationQuery.class), eq(12));
    }

    @Test
    void excludesRecentlyWatched() {
        when(recentHistoryStore.getRecentMovieIds(eq(USER.userId()), anyInt())).thenReturn(List.of(3));
        when(recallService.recall(any(RecommendationQuery.class), anyInt()))
                .thenReturn(List.of(cand(3, 0.9), cand(4, 0.5)));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 5));

        assertFalse(result.recommendations().stream().anyMatch(m -> m.id() == 3),
                "recently-watched movie must be excluded");
    }

    @Test
    void onlineLearnerReweightsRanking() {
        onlineLearner = new OnlineLearner(2.0, 0.0, 2.0);
        service = new OnlineRecommendationService(
                dataManager, recallService, recentHistoryStore, topkStore, onlineLearner);
        when(recallService.recall(any(RecommendationQuery.class), anyInt()))
                .thenReturn(List.of(cand(4, 0.9), cand(3, 0.8)));
        onlineLearner.learn(new ExperienceCollector.RecommendationExperience(
                "req-1", USER.userId(), 100L, 3,
                List.of(new ExperienceCollector.ItemFeedback(3, 1, 3, "order", Map.of()))));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 2));

        assertEquals(3, result.recommendations().get(0).id(),
                "learner boost on movie 3 should lift it above movie 4");
    }

    @Test
    void emptyRecallFallsBackToTrendingSnapshot() {
        when(recallService.recall(any(RecommendationQuery.class), anyInt())).thenReturn(List.of());
        when(topkStore.getTopKIds(eq("last_hour"), anyInt())).thenReturn(List.of("5", "6"));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 2));

        assertEquals("multichannel", result.strategy());
        assertEquals(List.of(5, 6), result.recommendations().stream().map(Movie::id).toList());
    }

    @Test
    void responseCarriesRecentAndTrendingSnapshot() {
        when(recentHistoryStore.getRecentMovieIds(eq(USER.userId()), anyInt())).thenReturn(List.of(3));
        when(topkStore.getTopKIds(eq("last_day"), anyInt())).thenReturn(List.of("4", "5"));
        when(recallService.recall(any(RecommendationQuery.class), anyInt())).thenReturn(List.of(cand(6, 0.9)));

        OnlineRecommendationResult result =
                service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_day", 5));

        assertEquals("last_day", result.window());
        assertEquals(List.of(3), result.recentMovies().stream().map(Movie::id).toList());
        assertEquals(List.of(4, 5), result.trendingMovies().stream().map(Movie::id).toList());
    }

    @Test
    void throwsUnknownUserExceptionForMissingUser() {
        OnlineRecommendationService.UnknownUserException ex = assertThrows(
                OnlineRecommendationService.UnknownUserException.class,
                () -> service.recommend(new OnlineRecommendationRequest(999, "last_hour", 5)));
        assertEquals(999, ex.userId());
    }

    @Test
    void rejectsInvalidWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recommend(new OnlineRecommendationRequest(USER.userId(), "bad_window", 5)));
    }

    @Test
    void primaryReadPreservesInvalidWindowAsClientError() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recommendPrimary(
                        new OnlineRecommendationRequest(USER.userId(), "bad_window", 5)));
    }

    @Test
    void recallLimitClampedToHundredForLargeK() {
        when(recallService.recall(any(RecommendationQuery.class), anyInt())).thenReturn(List.of());
        service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 30));
        // k=30 → unclamped would be 120; must be clamped to 100
        verify(recallService).recall(any(RecommendationQuery.class), eq(100));
    }

    @Test
    void appliedReadUsesPrimaryNoCacheForRecallAndResponseDynamicFeatures() {
        when(recentHistoryStore.getRecentMovieIdsPrimary(eq(USER.userId()), anyInt())).thenReturn(List.of());
        when(topkStore.getTopKIdsPrimary(eq("last_hour"), anyInt())).thenReturn(List.of());
        when(recallService.recallPrimary(any(RecommendationQuery.class), anyInt())).thenReturn(List.of());

        service.recommendPrimary(new OnlineRecommendationRequest(USER.userId(), "last_hour", 3));

        verify(recentHistoryStore).getRecentMovieIdsPrimary(eq(USER.userId()), anyInt());
        verify(topkStore).getTopKIdsPrimary(eq("last_hour"), anyInt());
        verify(recallService).recallPrimary(any(RecommendationQuery.class), anyInt());
        verify(recentHistoryStore, never()).getRecentMovieIds(eq(USER.userId()), anyInt());
        verify(topkStore, never()).getTopKIds(eq("last_hour"), anyInt());
        verify(recallService, never()).recall(any(RecommendationQuery.class), anyInt());
    }
}
