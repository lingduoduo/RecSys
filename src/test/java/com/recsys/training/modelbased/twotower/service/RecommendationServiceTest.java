package com.recsys.training.modelbased.twotower.service;

import com.recsys.training.modelbased.twotower.model.RecommendRequest;
import com.recsys.training.modelbased.twotower.model.ScoredItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationServiceTest {

    private CandidateSelectionService candidateSelectionService;
    private FeatureEncoder featureEncoder;
    private UserTowerInferenceService inferenceService;
    private RetrievalService retrievalService;
    private RankingService rankingService;
    private ModelArtifactService artifactService;
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        candidateSelectionService = mock(CandidateSelectionService.class);
        featureEncoder = mock(FeatureEncoder.class);
        inferenceService = mock(UserTowerInferenceService.class);
        retrievalService = mock(RetrievalService.class);
        rankingService = mock(RankingService.class);
        artifactService = mock(ModelArtifactService.class);
        service = new RecommendationService(
                candidateSelectionService, featureEncoder, inferenceService,
                retrievalService, rankingService, artifactService);
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
        float[] userEmb = {1.0f, 0.0f};
        var ranked = List.of(new ScoredItem("1", 0.95), new ScoredItem("3", 0.72));

        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(inferenceService.inferUserEmbedding(encoded)).thenReturn(userEmb);
        when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of("1", "2", "3"));
        when(retrievalService.recall(any(), any(), any(), anyInt())).thenReturn(ranked);
        when(rankingService.rank(any(), any(), anyInt())).thenReturn(ranked);
        when(artifactService.getModelVersion()).thenReturn("v1");

        var response = service.recommend(request("123", 2));

        assertThat(response.userId()).isEqualTo("123");
        assertThat(response.modelVersion()).isEqualTo("v1");
        assertThat(response.recommendations()).hasSize(2);
        assertThat(response.recommendations().get(0).itemId()).isEqualTo("1");
    }

    @Test
    void recommend_excludeItemIds_passedToCandidateSelection() {
        var encoded = new FeatureEncoder.EncodedFeatures(1L);
        when(featureEncoder.encode(any())).thenReturn(encoded);
        when(inferenceService.inferUserEmbedding(any())).thenReturn(new float[]{1.0f});
        when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of());
        when(retrievalService.recall(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(rankingService.rank(any(), any(), anyInt())).thenReturn(List.of());
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
        when(inferenceService.inferUserEmbedding(any())).thenReturn(new float[]{0.0f});
        when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of());
        when(retrievalService.recall(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(rankingService.rank(any(), any(), anyInt())).thenReturn(List.of());
        when(artifactService.getModelVersion()).thenReturn("v1");

        // non-numeric userId should not throw — null numericUserId is passed to candidate selection
        var response = service.recommend(request("alice", 3));
        assertThat(response).isNotNull();
    }

    private static RecommendRequest request(String userId, int k) {
        var req = new RecommendRequest();
        req.setUserId(userId);
        req.setK(k);
        return req;
    }
}
