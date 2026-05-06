package com.recsys.modelbased.model.service;

import com.recsys.modelbased.model.dto.ScoredItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        var artifactService = mock(ModelArtifactService.class);
        when(artifactService.getItemEmbeddings()).thenReturn(Map.of(
                "1", new float[]{1.0f, 0.0f},
                "2", new float[]{0.0f, 1.0f},
                "3", new float[]{0.5f, 0.5f},
                "4", new float[]{0.9f, 0.1f}
        ));
        retrievalService = new RetrievalService(artifactService);
    }

    @Test
    void recall_returnsTopKByInnerProduct() {
        // user aligned with dim-0: scores are item1=1.0, item4=0.9, item3=0.5, item2=0.0
        float[] userEmb = {1.0f, 0.0f};
        Set<String> candidates = Set.of("1", "2", "3", "4");

        List<ScoredItem> recalled = retrievalService.recall(userEmb, null, candidates, 2);

        assertThat(recalled).hasSize(2);
        assertThat(recalled).extracting(ScoredItem::itemId).containsExactly("1", "4");
    }

    @Test
    void recall_emptyUserEmbedding_returnsEmpty() {
        assertThat(retrievalService.recall(null, null, Set.of("1"), 5)).isEmpty();
    }

    @Test
    void recall_emptyCandidates_returnsEmpty() {
        assertThat(retrievalService.recall(new float[]{1.0f, 0.0f}, null, Set.of(), 5)).isEmpty();
    }

    @Test
    void recall_kZero_returnsEmpty() {
        assertThat(retrievalService.recall(
                new float[]{1.0f, 0.0f}, null, Set.of("1", "2"), 0)).isEmpty();
    }

    @Test
    void recall_candidateNotInEmbeddings_isIgnored() {
        Set<String> candidates = Set.of("1", "UNKNOWN_ITEM");
        List<ScoredItem> recalled = retrievalService.recall(
                new float[]{1.0f, 0.0f}, null, candidates, 5);

        assertThat(recalled).extracting(ScoredItem::itemId).doesNotContain("UNKNOWN_ITEM");
    }

    @Test
    void recall_recallSizeRespected() {
        float[] userEmb = {1.0f, 0.0f};
        Set<String> candidates = Set.of("1", "2", "3", "4");

        List<ScoredItem> recalled = retrievalService.recall(userEmb, null, candidates, 2);

        assertThat(recalled).hasSizeLessThanOrEqualTo(2);
    }
}
