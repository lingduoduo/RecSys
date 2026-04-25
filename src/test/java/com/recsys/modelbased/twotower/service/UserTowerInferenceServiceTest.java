package com.recsys.modelbased.twotower.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTowerInferenceServiceTest {

    private static final int EMBEDDING_DIM = 16;

    private UserTowerInferenceService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new UserTowerInferenceService(new ModelArtifactLocator("", ""));
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
    void infer_knownUserIndex_returnsEmbeddingOfCorrectDimension() {
        float[] embedding = service.inferUserEmbedding(new FeatureEncoder.EncodedFeatures(1L));
        assertThat(embedding).hasSize(EMBEDDING_DIM);
    }

    @Test
    void infer_unknownUserIndex_returnsEmbeddingOfCorrectDimension() {
        float[] embedding = service.inferUserEmbedding(new FeatureEncoder.EncodedFeatures(0L));
        assertThat(embedding).hasSize(EMBEDDING_DIM);
    }

    @Test
    void infer_distinctUserIndices_produceDifferentEmbeddings() {
        float[] emb1 = service.inferUserEmbedding(new FeatureEncoder.EncodedFeatures(1L));
        float[] emb2 = service.inferUserEmbedding(new FeatureEncoder.EncodedFeatures(2L));
        assertThat(emb1).isNotEqualTo(emb2);
    }

    @Test
    void infer_sameUserIndex_producesSameEmbedding() {
        float[] emb1 = service.inferUserEmbedding(new FeatureEncoder.EncodedFeatures(1L));
        float[] emb2 = service.inferUserEmbedding(new FeatureEncoder.EncodedFeatures(1L));
        assertThat(emb1).isEqualTo(emb2);
    }
}
