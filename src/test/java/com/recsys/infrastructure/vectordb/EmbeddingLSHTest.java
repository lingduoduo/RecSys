package com.recsys.infrastructure.vectordb;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingLSHTest {

    @Test
    void add_newVecAppearsInCandidates() {
        EmbeddingLSH lsh = new EmbeddingLSH(Map.of(1, new float[]{1f, 0f}));
        lsh.add(99, new float[]{1f, 0f});
        assertThat(lsh.candidates(new float[]{1f, 0f})).contains(99);
    }

    @Test
    void add_duplicateIdIsAddedAgainWithoutError() {
        EmbeddingLSH lsh = new EmbeddingLSH(Map.of(1, new float[]{1f, 0f}));
        lsh.add(1, new float[]{0f, 1f}); // should not throw
        assertThat(lsh.candidates(new float[]{0f, 1f})).contains(1);
    }
}
