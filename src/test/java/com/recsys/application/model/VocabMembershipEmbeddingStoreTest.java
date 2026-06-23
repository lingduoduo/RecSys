package com.recsys.application.model;
import com.recsys.application.model.VocabMembershipEmbeddingStore;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VocabMembershipEmbeddingStoreTest {

    @Test
    void presentForVocabMember_nullForUnknownAndUnk() {
        var store = new VocabMembershipEmbeddingStore(Map.of("123", 1, "200", 2, "__UNK__", 0));
        assertThat(store.getEmbedding(123)).isNotNull();   // in vocab -> warm
        assertThat(store.getEmbedding(200)).isNotNull();
        assertThat(store.getEmbedding(999)).isNull();      // not in vocab -> cold
        assertThat(store.getEmbedding(0)).isNull();        // "0" is not a member (__UNK__ excluded)
    }

    @Test
    void inertWriteAndBulkMethodsDoNotThrow() {
        var store = new VocabMembershipEmbeddingStore(Map.of("1", 1));
        assertThat(store.getEmbeddings(java.util.List.of(1, 2))).isEmpty();
        assertThat(store.scanIds(10)).isEmpty();
        store.setEmbedding(1, new float[]{0f}, 0L);                 // no-op
        store.setEmbeddings(Map.of(1, new float[]{0f}), 0L);       // no-op
    }
}
