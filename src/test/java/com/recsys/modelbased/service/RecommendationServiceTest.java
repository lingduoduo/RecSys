package com.recsys.modelbased.service;

import com.recsys.modelbased.dto.RecommendRequest;
import com.recsys.modelbased.dto.ScoredItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationServiceTest {

    private CandidateSelectionService candidateSelectionService;
    private FeatureEncoder featureEncoder;
    private UserTowerInferenceService inferenceService;
    private ModelArtifactService artifactService;
    private ModelRuntimeProvider modelRuntimeProvider;
    private ABTestService abTestService;
    private RecommendationService service;
    private ModelRuntime runtime;

    @BeforeEach
    void setUp() {
        candidateSelectionService = mock(CandidateSelectionService.class);
        featureEncoder = mock(FeatureEncoder.class);
        inferenceService = mock(UserTowerInferenceService.class);
        artifactService = mock(ModelArtifactService.class);
        modelRuntimeProvider = mock(ModelRuntimeProvider.class);
        abTestService = mock(ABTestService.class);
        when(abTestService.getAssignmentForUser(any())).thenReturn(
                new ABTestService.Assignment("training", -1, "default", false)
        );
        runtime = new ModelRuntime(
                "training",
                artifactService,
                candidateSelectionService,
                featureEncoder,
                inferenceService
        );
        when(modelRuntimeProvider.getRuntime(any())).thenReturn(runtime);
        service = new RecommendationService(modelRuntimeProvider, abTestService);
    }

    // ---- validation ----

    @Test
    void recommend_nullUserId_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.recommend(request(null, 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void recommend_blankUserId_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.recommend(request("  ", 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void recommend_kZero_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.recommend(request("123", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k");
    }

    @Test
    void recommend_kAbove100_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.recommend(request("123", 101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k");
    }

    // ---- happy path ----

    @Test
    void recommend_validRequest_returnsRankedItems() {
        var encoded = new FeatureEncoder.EncodedFeatures(1L);
        var ranked = List.of(new ScoredItem("1", 0.95), new ScoredItem("3", 0.72));

        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of("1", "2", "3"));
        when(inferenceService.scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt())).thenReturn(ranked);
        when(artifactService.getModelVersion()).thenReturn("v1");

        var response = service.recommend(request("123", 2));

        assertThat(response.userId()).isEqualTo("123");
        assertThat(response.modelVersion()).isEqualTo("v1");
        assertThat(response.recommendations()).hasSize(2);
        assertThat(response.recommendations().get(0).itemId()).isEqualTo("1");
    }

    @Test
    void recommend_sameRequest_returnsCachedItemsWithoutRescoring() {
        var encoded = new FeatureEncoder.EncodedFeatures(1L);
        var ranked = List.of(new ScoredItem("1", 0.95), new ScoredItem("3", 0.72));

        when(artifactService.getUserVocab()).thenReturn(Map.of("123", 1));
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of("1", "2", "3"));
        when(inferenceService.scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt())).thenReturn(ranked);
        when(artifactService.getModelVersion()).thenReturn("v1");

        var first = service.recommend(request("123", 2));
        var second = service.recommend(request("123", 2));

        assertThat(second.recommendations()).isEqualTo(first.recommendations());
        verify(inferenceService, times(1)).scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt());
        verify(candidateSelectionService, times(1)).selectCandidates(123, Set.of());
    }

    @Test
    void recommend_unknownUsers_shareColdStartCache() {
        var encoded = new FeatureEncoder.EncodedFeatures(0L);
        var ranked = List.of(new ScoredItem("1", 0.91), new ScoredItem("2", 0.80), new ScoredItem("3", 0.70));

        when(artifactService.getUserVocab()).thenReturn(Map.of("__UNK__", 0));
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of("1", "2", "3"));
        when(inferenceService.scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt())).thenReturn(ranked);
        when(artifactService.getModelVersion()).thenReturn("v1");

        var first = service.recommend(request("new-user-a", 2));
        var second = service.recommend(request("new-user-b", 2));

        assertThat(first.recommendations()).containsExactlyElementsOf(ranked.subList(0, 2));
        assertThat(second.userId()).isEqualTo("new-user-b");
        assertThat(second.recommendations()).containsExactlyElementsOf(ranked.subList(0, 2));
        verify(inferenceService, times(1)).scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt());
        verify(candidateSelectionService, times(1)).selectCandidates(null, Set.of());
    }

    @Test
    void recommend_excludeItemIds_passedToCandidateSelection() {
        var encoded = new FeatureEncoder.EncodedFeatures(1L);
        when(artifactService.getUserVocab()).thenReturn(Map.of("123", 1));
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of());
        when(inferenceService.scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt())).thenReturn(List.of());
        when(artifactService.getModelVersion()).thenReturn("v1");

        var req = request("123", 5);
        req.setExcludeItemIds(List.of("2", "5"));
        service.recommend(req);

        org.mockito.Mockito.verify(candidateSelectionService)
                .selectCandidates(123, Set.of("2", "5"));
    }

    @Test
    void recommend_nonNumericUserId_treatedAsUnknown() {
        var encoded = new FeatureEncoder.EncodedFeatures(0L);
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of());
        when(inferenceService.scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt())).thenReturn(List.of());
        when(artifactService.getModelVersion()).thenReturn("v1");

        // non-numeric userId should not throw — null numericUserId is passed to candidate selection
        var response = service.recommend(request("alice", 3));
        assertThat(response).isNotNull();
    }

    @Test
    void recommend_coldStartFlagDisabled_runsFullInferenceForEachUnknownUser() {
        var flagService = new com.recsys.featureflags.FeatureFlagService(
                (flag, id, props) -> "cold-start-enabled".equals(flag.key())
                        ? Optional.of(false)
                        : Optional.empty());
        var testService = new RecommendationService(
                modelRuntimeProvider, abTestService,
                new com.recsys.modelbased.config.RecommendationCacheProperties(),
                flagService);

        var encoded = new FeatureEncoder.EncodedFeatures(0L);
        when(artifactService.getUserVocab()).thenReturn(Map.of("__UNK__", 0));
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of("1", "2"));
        when(inferenceService.scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt()))
                .thenReturn(List.of(new ScoredItem("1", 0.9), new ScoredItem("2", 0.8)));
        when(artifactService.getModelVersion()).thenReturn("v1");

        testService.recommend(request("new-user-a", 1));
        testService.recommend(request("new-user-b", 1));

        // flag disabled → no cold-start pool → each unknown user triggers its own inference call
        verify(inferenceService, times(2)).scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt());
    }

    private static RecommendRequest request(String userId, int k) {
        var req = new RecommendRequest();
        req.setUserId(userId);
        req.setK(k);
        return req;
    }
}
