package com.recsys.modelbased.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelArtifactServiceTest {

    private ModelArtifactService service;

    @BeforeEach
    void setUp() throws IOException {
        var locator = new ModelArtifactLocator("", "");
        service = new ModelArtifactService(locator);
        service.loadArtifacts();
    }

    @Test
    void modelVersion_matchesBundledConfig() {
        assertThat(service.getModelVersion()).isEqualTo("demo-two-tower-ratings-v1");
    }

    @Test
    void userVocab_containsKnownUsers() {
        var vocab = service.getUserVocab();
        assertThat(vocab).containsKey("__UNK__");
        assertThat(vocab).containsKey("123");
        assertThat(vocab).containsKey("124");
        assertThat(vocab.get("__UNK__")).isEqualTo(0);
    }

    @Test
    void itemEmbeddings_loadedWithCorrectDimension() {
        var embeddings = service.getItemEmbeddings();
        assertThat(embeddings).isNotEmpty();
        embeddings.values().forEach(vec ->
                assertThat(vec).hasSize(16));
    }

    @Test
    void itemEmbeddings_containsKnownItems() {
        assertThat(service.getItemEmbeddings()).containsKey("1");
    }

    @Test
    void itemEmbeddings_isImmutable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.getItemEmbeddings().put("99", new float[16]))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
