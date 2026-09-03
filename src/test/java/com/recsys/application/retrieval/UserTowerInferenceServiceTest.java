package com.recsys.application.retrieval;
import com.recsys.application.feature.FeatureEncoder;
import com.recsys.application.model.ModelArtifactLocator;
import com.recsys.application.model.ModelArtifactService;
import com.recsys.application.retrieval.UserTowerInferenceService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTowerInferenceServiceTest {

    private UserTowerInferenceService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new UserTowerInferenceService(new ModelArtifactLocator("", ""), "");
        service.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    @Test
    void init_loadsOnnxSession_withoutException() {
        assertThat(service).isNotNull();
    }

    @Test
    void initValidatesMetadataAndRunsSmokeInference() {
        // The bundled demo model satisfies the legacy contract; init must have already paid
        // exactly one native run (the smoke inference) before reporting ready.
        assertThat(service.isReady()).isTrue();
        assertThat(service.runCount()).isEqualTo(1);
    }

    @Test
    void everyNativeRunIsCounted() {
        long before = service.runCount();
        service.score(new FeatureEncoder.EncodedFeatures(1L), 1L);
        service.score(new FeatureEncoder.EncodedFeatures(1L), 2L);
        assertThat(service.runCount()).isEqualTo(before + 2);
    }

    @Test
    void closeClearsReadinessAndIsIdempotent() throws Exception {
        service.close();
        service.close();
        assertThat(service.isReady()).isFalse();
        assertThatThrownBy(() -> service.score(new FeatureEncoder.EncodedFeatures(1L), 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void score_knownUserAndItem_returnsFiniteScore() {
        double score = service.score(new FeatureEncoder.EncodedFeatures(1L), 1L);
        assertThat(score).isFinite();
    }

    @Test
    void score_unknownUserIndex_returnsFiniteScore() {
        double score = service.score(new FeatureEncoder.EncodedFeatures(0L), 1L);
        assertThat(score).isFinite();
    }

    @Test
    void score_distinctItems_produceDifferentScores() {
        double score1 = service.score(new FeatureEncoder.EncodedFeatures(1L), 1L);
        double score2 = service.score(new FeatureEncoder.EncodedFeatures(1L), 2L);
        assertThat(score1).isNotEqualTo(score2);
    }

    @Test
    void score_samePair_producesSameScore() {
        double score1 = service.score(new FeatureEncoder.EncodedFeatures(1L), 1L);
        double score2 = service.score(new FeatureEncoder.EncodedFeatures(1L), 1L);
        assertThat(score1).isEqualTo(score2);
    }

    @Test
    void scoreCandidates_scoresBatchAndKeepsTopK() {
        FeatureEncoder encoder = new FeatureEncoder(new StubArtifactService());

        var scores = service.scoreCandidates(
                new FeatureEncoder.EncodedFeatures(1L),
                encoder,
                Set.of("1", "2", "3"),
                2
        );

        assertThat(scores).hasSize(2);
        assertThat(scores.get(0).score()).isGreaterThanOrEqualTo(scores.get(1).score());
        assertThat(scores).allSatisfy(item -> assertThat(Set.of("1", "2", "3")).contains(item.itemId()));
    }

    private static final class StubArtifactService extends ModelArtifactService {
        StubArtifactService() {
            super(new ModelArtifactLocator("", ""), null);
        }

        @Override
        public Map<String, Integer> getUserVocab() {
            return Map.of("__UNK__", 0, "1", 1);
        }

        @Override
        public Map<String, Integer> getItemVocab() {
            return Map.of("1", 1, "2", 2, "3", 3);
        }
    }
}
