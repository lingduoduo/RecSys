package com.recsys.streaming;

import com.recsys.features.CandidateGenerator;
import com.recsys.features.DataManager;
import com.recsys.models.Movie;
import com.recsys.models.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineRecommendationServiceTest {

    private static final User USER = new User(123, "Alice");
    private static final Movie M1  = new Movie(1,  "A", 2020, List.of("Drama"));
    private static final Movie M2  = new Movie(2,  "B", 2021, List.of("Drama"));
    private static final Movie M3  = new Movie(3,  "C", 2022, List.of("Action"));
    private static final Movie M4  = new Movie(4,  "D", 2019, List.of("Action"));
    private static final Movie M5  = new Movie(5,  "E", 2018, List.of("Comedy"));
    private static final Movie M6  = new Movie(6,  "F", 2017, List.of("Comedy"));

    private DataManager dataManager;
    private OnlineRecommendationEngine engine;
    private CandidateGenerator candidateGenerator;
    private OnlineRecommendationService service;

    @BeforeEach
    void setUp() {
        dataManager      = mock(DataManager.class);
        engine           = mock(OnlineRecommendationEngine.class);
        candidateGenerator = mock(CandidateGenerator.class);
        service          = new OnlineRecommendationService(dataManager, engine, candidateGenerator);

        when(dataManager.getUserById(USER.userId())).thenReturn(USER);
        when(dataManager.getUserById(999)).thenReturn(null);
    }

    @Test
    void blendsMergesOnlineAndModelCandidates() {
        // Online: [M3, M4] ranked; recently watched [M1, M2]
        stubEngine(List.of(M1, M2), List.of(M3), List.of(M3, M4));
        // Model: [M5, M6, M3] — M3 appears in both, should rank highest
        when(candidateGenerator.byEmbedding(eq(USER.userId()), anyInt()))
                .thenReturn(List.of(M5, M6, M3));

        OnlineRecommendationResult result = service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 5));

        assertEquals("online+model", result.strategy());
        // M3 ranks in both lists — it must come first
        assertEquals(M3.id(), result.recommendations().get(0).id());
        // Recently watched must be excluded
        assertTrue(result.recommendations().stream().noneMatch(m -> m.id() == M1.id() || m.id() == M2.id()));
    }

    @Test
    void fallsBackToOnlineOnlyWhenNoEmbedding() {
        stubEngine(List.of(), List.of(M3), List.of(M3, M4, M5));
        when(candidateGenerator.byEmbedding(eq(USER.userId()), anyInt()))
                .thenReturn(List.of());

        OnlineRecommendationResult result = service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 2));

        assertEquals("online", result.strategy());
        assertEquals(2, result.recommendations().size());
    }

    @Test
    void excludesRecentlyWatchedFromBlendedOutput() {
        // M3 is in recently watched; it must not appear in recommendations
        stubEngine(List.of(M3), List.of(M3), List.of(M4, M5));
        when(candidateGenerator.byEmbedding(eq(USER.userId()), anyInt()))
                .thenReturn(List.of(M3, M6));

        OnlineRecommendationResult result = service.recommend(new OnlineRecommendationRequest(USER.userId(), "last_hour", 5));

        assertFalse(result.recommendations().stream().anyMatch(m -> m.id() == M3.id()),
                "recently-watched movie must be excluded");
    }

    @Test
    void throwsUnknownUserExceptionForMissingUser() {
        OnlineRecommendationService.UnknownUserException ex = assertThrows(
                OnlineRecommendationService.UnknownUserException.class,
                () -> service.recommend(new OnlineRecommendationRequest(999, "last_hour", 5)));
        assertEquals(999, ex.userId());
    }

    @Test
    void propagatesWindowValidationFromEngine() {
        when(engine.recommend(eq(USER.userId()), eq("bad_window"), anyInt()))
                .thenThrow(new IllegalArgumentException("invalid window: bad_window"));

        assertThrows(IllegalArgumentException.class,
                () -> service.recommend(new OnlineRecommendationRequest(USER.userId(), "bad_window", 5)));
    }

    // ---- helpers ----

    private void stubEngine(List<Movie> recent, List<Movie> trending, List<Movie> recs) {
        when(engine.recommend(eq(USER.userId()), anyString(), anyInt()))
                .thenReturn(new OnlineRecommendationEngine.OnlineRecommendationResult(
                        "last_hour", recent, trending, recs));
    }
}
