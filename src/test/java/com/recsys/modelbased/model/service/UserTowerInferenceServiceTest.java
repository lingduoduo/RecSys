package com.recsys.modelbased.model.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
