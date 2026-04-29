package com.recsys.modelbased.model.service;

import com.recsys.modelbased.model.dto.RecommendRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeatureEncoderTest {

    private FeatureEncoder encoder;

    @BeforeEach
    void setUp() {
        var artifactService = mock(ModelArtifactService.class);
        when(artifactService.getUserVocab()).thenReturn(
                Map.of("__UNK__", 0, "123", 1, "124", 2, "125", 3));
        encoder = new FeatureEncoder(artifactService);
    }

    @Test
    void encode_knownUser_returnsMappedIndex() {
        assertThat(encode("123")).isEqualTo(1L);
        assertThat(encode("124")).isEqualTo(2L);
        assertThat(encode("125")).isEqualTo(3L);
    }

    @Test
    void encode_unknownUser_returnsUnkIndex() {
        assertThat(encode("999")).isEqualTo(0L);
    }

    @Test
    void encode_unkToken_returnsZero() {
        assertThat(encode("__UNK__")).isEqualTo(0L);
    }

    private long encode(String userId) {
        var req = new RecommendRequest();
        req.setUserId(userId);
        return encoder.encode(req).getUserId();
    }
}
