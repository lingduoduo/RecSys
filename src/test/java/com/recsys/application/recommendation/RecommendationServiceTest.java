package com.recsys.application.recommendation;
import com.recsys.application.experiment.VariantRuntimeResolver;
import com.recsys.application.model.ModelRuntime;
import com.recsys.application.recommendation.RecommendationService;
import com.recsys.application.experiment.ABTestService;
import com.recsys.application.model.ModelRuntimeProvider;
import com.recsys.application.model.ModelArtifactService;
import com.recsys.application.retrieval.UserTowerInferenceService;
import com.recsys.application.feature.FeatureEncoder;
import com.recsys.application.ranking.RankingStage;
import com.recsys.application.retrieval.ModelRetrievalStage;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.infrastructure.featureflags.FeatureFlagService;
import com.recsys.infrastructure.featureflags.Flags;
import com.recsys.config.RecommendationCacheProperties;
import com.recsys.api.request.RecommendRequest;
import com.recsys.api.response.RecommendResponse;
import com.recsys.domain.prediction.ScoredItem;
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

    private ModelRetrievalStage retrievalStage;
    private RankingStage rankingStage;
    private FeatureEncoder featureEncoder;
    private UserTowerInferenceService inferenceService;
    private ModelArtifactService artifactService;
    private ModelRuntimeProvider modelRuntimeProvider;
    private ABTestService abTestService;
    private RecommendationService service;
    private ModelRuntime runtime;

    @BeforeEach
    void setUp() {
        retrievalStage = mock(ModelRetrievalStage.class);
        rankingStage = mock(RankingStage.class);
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
                retrievalStage,
                rankingStage,
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
        var candidates = List.of(
                new MovieCandidate("1", 0.9, "embedding", Map.of()),
                new MovieCandidate("2", 0.8, "embedding", Map.of()),
                new MovieCandidate("3", 0.7, "embedding", Map.of())
        );
        var ranked = List.of(new ScoredItem("1", 0.95), new ScoredItem("3", 0.72));

        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(retrievalStage.retrieve(any(), anyInt())).thenReturn(candidates);
        when(rankingStage.rank(eq(encoded), eq(candidates), anyInt())).thenReturn(ranked);
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
        var candidates = List.of(
                new MovieCandidate("1", 0.9, "embedding", Map.of()),
                new MovieCandidate("3", 0.7, "embedding", Map.of())
        );
        var ranked = List.of(new ScoredItem("1", 0.95), new ScoredItem("3", 0.72));

        when(artifactService.getUserVocab()).thenReturn(Map.of("123", 1));
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(retrievalStage.retrieve(any(), anyInt())).thenReturn(candidates);
        when(rankingStage.rank(eq(encoded), eq(candidates), anyInt())).thenReturn(ranked);
        when(artifactService.getModelVersion()).thenReturn("v1");

        var first = service.recommend(request("123", 2));
        var second = service.recommend(request("123", 2));

        assertThat(second.recommendations()).isEqualTo(first.recommendations());
        // Cache hit: rank called only once despite two recommend() calls
        verify(rankingStage, times(1)).rank(eq(encoded), eq(candidates), anyInt());
    }

    @Test
    void recommend_unknownUsers_shareColdStartCache() {
        var encoded = new FeatureEncoder.EncodedFeatures(0L);
        var candidates = List.of(
                new MovieCandidate("1", 0.9, "cold", Map.of()),
                new MovieCandidate("2", 0.8, "cold", Map.of()),
                new MovieCandidate("3", 0.7, "cold", Map.of())
        );
        var ranked = List.of(new ScoredItem("1", 0.91), new ScoredItem("2", 0.80), new ScoredItem("3", 0.70));

        when(artifactService.getUserVocab()).thenReturn(Map.of("__UNK__", 0));
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(retrievalStage.retrieve(any(), anyInt())).thenReturn(candidates);
        when(rankingStage.rank(eq(encoded), eq(candidates), anyInt())).thenReturn(ranked);
        when(artifactService.getModelVersion()).thenReturn("v1");

        var first = service.recommend(request("new-user-a", 2));
        var second = service.recommend(request("new-user-b", 2));

        assertThat(first.recommendations()).containsExactlyElementsOf(ranked.subList(0, 2));
        assertThat(second.userId()).isEqualTo("new-user-b");
        assertThat(second.recommendations()).containsExactlyElementsOf(ranked.subList(0, 2));
        // Cold-start pool is shared — rankingStage called once for both unknown users
        verify(rankingStage, times(1)).rank(eq(encoded), eq(candidates), anyInt());
    }

    @Test
    void recommend_excludeItemIds_passedToRetrievalStage() {
        var encoded = new FeatureEncoder.EncodedFeatures(1L);
        when(artifactService.getUserVocab()).thenReturn(Map.of("123", 1));
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(retrievalStage.retrieve(any(), anyInt())).thenReturn(List.of());
        // recall empty → fallback; artifactService returns empty vocab ids so fallback is also empty
        when(artifactService.getAvailableItemIds()).thenReturn(Set.of());
        when(artifactService.getModelVersion()).thenReturn("v1");
        when(rankingStage.rank(any(), any(), anyInt())).thenReturn(List.of());

        var req = request("123", 5);
        req.setExcludeItemIds(List.of("2", "5"));
        service.recommend(req);

        // The excludedItemIds set is forwarded in the RecommendationQuery to the retrievalStage
        var captor = org.mockito.ArgumentCaptor.forClass(com.recsys.domain.recommendation.RecommendationQuery.class);
        verify(retrievalStage).retrieve(captor.capture(), anyInt());
        assertThat(captor.getValue().excludedItemIds()).contains("2", "5");
    }

    @Test
    void recommend_nonNumericUserId_treatedAsUnknown() {
        var encoded = new FeatureEncoder.EncodedFeatures(0L);
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(retrievalStage.retrieve(any(), anyInt())).thenReturn(List.of());
        when(artifactService.getAvailableItemIds()).thenReturn(Set.of());
        when(rankingStage.rank(any(), any(), anyInt())).thenReturn(List.of());
        when(artifactService.getModelVersion()).thenReturn("v1");

        // non-numeric userId should not throw
        var response = service.recommend(request("alice", 3));
        assertThat(response).isNotNull();
    }

    @Test
    void recommend_coldStartFlagDisabled_runsFullInferenceForEachUnknownUser() {
        var flagService = new FeatureFlagService(
                (flag, id, props) -> flag.key().equals(Flags.COLD_START_ENABLED.key())
                        ? Optional.of(false)
                        : Optional.empty());
        var testService = new RecommendationService(
                modelRuntimeProvider, abTestService,
                new RecommendationCacheProperties(),
                flagService);

        var encoded = new FeatureEncoder.EncodedFeatures(0L);
        var candidates = List.of(
                new MovieCandidate("1", 0.9, "embedding", Map.of()),
                new MovieCandidate("2", 0.8, "embedding", Map.of())
        );
        var ranked = List.of(new ScoredItem("1", 0.9), new ScoredItem("2", 0.8));

        when(artifactService.getUserVocab()).thenReturn(Map.of("__UNK__", 0));
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(retrievalStage.retrieve(any(), anyInt())).thenReturn(candidates);
        when(rankingStage.rank(eq(encoded), eq(candidates), anyInt())).thenReturn(ranked);
        when(artifactService.getModelVersion()).thenReturn("v1");

        testService.recommend(request("new-user-a", 1));
        testService.recommend(request("new-user-b", 1));

        // flag disabled → no cold-start pool → each unknown user triggers its own ranking call
        verify(rankingStage, times(2)).rank(eq(encoded), eq(candidates), anyInt());
    }

    // ---- empty-recall fallback ----

    @Test
    void recommend_emptyRecall_fallsBackToCandidateSelectionService() {
        var encoded = new FeatureEncoder.EncodedFeatures(1L);
        var ranked = List.of(new ScoredItem("1", 0.95), new ScoredItem("2", 0.80));

        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(retrievalStage.retrieve(any(), anyInt())).thenReturn(List.of());
        // Stub getAvailableItemIds so CandidateSelectionService has something to return
        when(artifactService.getAvailableItemIds()).thenReturn(Set.of("1", "2", "3"));
        when(artifactService.getUserVocab()).thenReturn(Map.of("123", 1));
        when(artifactService.getItemVocab()).thenReturn(Map.of("1", 1, "2", 2, "3", 3));
        when(artifactService.getModelVersion()).thenReturn("v1");
        // rankingStage is called with a NON-EMPTY candidate list from the fallback
        when(rankingStage.rank(eq(encoded), any(), anyInt())).thenReturn(ranked);

        var response = service.recommend(request("123", 2));

        // Verify rankingStage was called with a non-empty candidate list (fallback produced candidates)
        @SuppressWarnings("unchecked")
        var captor = (org.mockito.ArgumentCaptor<List<MovieCandidate>>)
                (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(List.class);
        verify(rankingStage).rank(eq(encoded), captor.capture(), anyInt());
        assertThat(captor.getValue()).isNotEmpty();
        assertThat(captor.getValue()).allMatch(c -> "fallback".equals(c.channel()));
        assertThat(response.recommendations()).isNotEmpty();
    }

    @Test
    void servedVariantFromResolverDrivesResponse() {
        VariantRuntimeResolver resolver = mock(VariantRuntimeResolver.class);
        when(resolver.resolve(eq("test"), any()))
                .thenReturn(new VariantRuntimeResolver.Resolved(runtime, "training", true));
        when(abTestService.defaultVariant()).thenReturn("training");
        when(abTestService.getAssignmentForUser(any()))
                .thenReturn(new ABTestService.Assignment("test", 10, "default", true));

        // reuse existing stubs so recommend() returns items
        var encoded = new FeatureEncoder.EncodedFeatures(1L);
        var candidates = List.of(new MovieCandidate("1", 0.9, "embedding", Map.of()));
        var ranked = List.of(new ScoredItem("1", 0.95));
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(retrievalStage.retrieve(any(), anyInt())).thenReturn(candidates);
        when(rankingStage.rank(eq(encoded), eq(candidates), anyInt())).thenReturn(ranked);
        when(artifactService.getModelVersion()).thenReturn("v1");

        // Build the service with the mocked resolver via the full 6-arg constructor.
        RecommendationService svc = new RecommendationService(
                modelRuntimeProvider, abTestService, new RecommendationCacheProperties(),
                org.mockito.Mockito.mock(com.recsys.infrastructure.featureflags.FeatureFlagService.class),
                new com.recsys.api.converter.RecommendationConverter(), resolver);

        RecommendResponse response = svc.recommend(request("123", 5));

        assertThat(response.abTestVariant()).isEqualTo("training"); // served, not assigned
    }

    private static RecommendRequest request(String userId, int k) {
        var req = new RecommendRequest();
        req.setUserId(userId);
        req.setK(k);
        return req;
    }
}
