package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.model.dto.ScoredItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RankingStageTest {

    private RankingStage stageWith(Map<String, Integer> itemVocab, List<ScoredItem> onnxResult) {
        UserTowerInferenceService inference = mock(UserTowerInferenceService.class);
        ModelArtifactService artifacts = mock(ModelArtifactService.class);
        when(artifacts.getItemVocab()).thenReturn(itemVocab);
        when(artifacts.getUserVocab()).thenReturn(Map.of("__UNK__", 0));
        FeatureEncoder encoder = new FeatureEncoder(artifacts);
        when(inference.scoreCandidates(any(), any(), anySet(), anyInt())).thenReturn(onnxResult);
        return new RankingStage(inference, encoder, artifacts);
    }

    @Test
    void inVocabOnnxRanked_outOfVocabAppendedByRecallScore() {
        RankingStage stage = stageWith(
                Map.of("1", 1, "2", 2),                                  // 1,2 in vocab; 3,4 out
                List.of(new ScoredItem("2", 9.0), new ScoredItem("1", 5.0)));
        List<MovieCandidate> candidates = List.of(
                new MovieCandidate("1", 0.3, "embedding", Map.of()),
                new MovieCandidate("2", 0.2, "embedding", Map.of()),
                new MovieCandidate("3", 0.8, "trending", Map.of()),     // out-of-vocab, high recall score
                new MovieCandidate("4", 0.1, "popularity", Map.of()));

        List<ScoredItem> ranked = stage.rank(new FeatureEncoder.EncodedFeatures(0), candidates, 4);

        assertThat(ranked).extracting(ScoredItem::itemId).containsExactly("2", "1", "3", "4");
    }

    @Test
    void modelItemsFillK_dropsOutOfVocab() {
        RankingStage stage = stageWith(
                Map.of("1", 1, "2", 2),
                List.of(new ScoredItem("2", 9.0), new ScoredItem("1", 5.0)));
        List<MovieCandidate> candidates = List.of(
                new MovieCandidate("1", 0.3, "embedding", Map.of()),
                new MovieCandidate("2", 0.2, "embedding", Map.of()),
                new MovieCandidate("3", 0.8, "trending", Map.of()));

        List<ScoredItem> ranked = stage.rank(new FeatureEncoder.EncodedFeatures(0), candidates, 2);

        assertThat(ranked).extracting(ScoredItem::itemId).containsExactly("2", "1"); // out-of-vocab 3 dropped
    }

    @Test
    void emptyCandidatesOrNonPositiveK_returnsEmpty() {
        RankingStage stage = stageWith(Map.of("1", 1), List.of());
        assertThat(stage.rank(new FeatureEncoder.EncodedFeatures(0), List.of(), 5)).isEmpty();
        assertThat(stage.rank(new FeatureEncoder.EncodedFeatures(0),
                List.of(new MovieCandidate("1", 0.5, "c", Map.of())), 0)).isEmpty();
    }

    @Test
    void duplicateItemIdAppearsOnceInOutput() {
        RankingStage stage = stageWith(
                Map.of("1", 1, "2", 2),                                  // 1,2 in vocab
                List.of(new ScoredItem("1", 9.0), new ScoredItem("2", 5.0)));
        List<MovieCandidate> candidates = List.of(
                new MovieCandidate("1", 0.3, "embedding", Map.of()),
                new MovieCandidate("1", 0.9, "trending", Map.of()),     // duplicate itemId
                new MovieCandidate("2", 0.2, "embedding", Map.of()));

        List<ScoredItem> ranked = stage.rank(new FeatureEncoder.EncodedFeatures(0), candidates, 5);

        assertThat(ranked).extracting(ScoredItem::itemId).containsExactly("1", "2");  // "1" once, ONNX order
    }
}
