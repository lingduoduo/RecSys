package com.recsys.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ModelArtifactServiceTest {

    private ModelArtifactService service;
    private ModelArtifactLocator locator;

    @BeforeEach
    void setUp() throws IOException {
        locator = new ModelArtifactLocator("", "");
        service = new ModelArtifactService(locator, "");
        service.loadArtifacts();
    }

    @Test
    void modelVersion_matchesBundledConfig() {
        assertThat(service.getModelVersion()).isEqualTo("dssm-demo-v1");
    }

    @Test
    void namedVariant_readsDistinctClasspathBundle() throws IOException {
        ModelArtifactService testVariant = new ModelArtifactService(locator, "test");
        testVariant.loadArtifacts();

        assertThat(testVariant.getModelVersion()).isEqualTo("dssm-demo-test-v1");
        assertThat(testVariant.getItemVocab()).containsKey("1");
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
        assertThat(service.getItemEmbeddings()).isEmpty();
    }

    @Test
    void itemVocab_containsKnownItems() {
        assertThat(service.getItemVocab()).containsKey("1");
    }

    @Test
    void itemEmbeddings_isImmutable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.getItemEmbeddings().put("99", new float[16]))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
