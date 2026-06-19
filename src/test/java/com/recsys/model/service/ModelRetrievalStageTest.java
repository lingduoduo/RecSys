package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRetrievalStageTest {

    @Test
    void retrieve_delegatesToRecallService() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        RecommendationQuery query = new RecommendationQuery("123", 50, Set.of(), null);
        List<MovieCandidate> expected = List.of(new MovieCandidate("4", 0.9, "trending", Map.of()));
        when(recall.recall(query, 50)).thenReturn(expected);

        ModelRetrievalStage stage = new ModelRetrievalStage(recall);

        assertThat(stage.retrieve(query, 50)).isEqualTo(expected);
    }
}
