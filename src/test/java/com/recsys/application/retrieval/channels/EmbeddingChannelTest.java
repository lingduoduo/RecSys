package com.recsys.application.retrieval.channels;

import com.recsys.domain.item.Movie;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingChannelTest {

    @Test
    void recall_returnsCandidatesForKnownUser() {
        CandidateGenerator cg = mock(CandidateGenerator.class);
        Movie m1 = new Movie(10, "Alpha", 2020, List.of("Action"));
        Movie m2 = new Movie(20, "Beta", 2021, List.of("Drama"));
        when(cg.byEmbedding(42, 10)).thenReturn(List.of(m1, m2));

        Channels.Embedding channel = new Channels.Embedding(cg);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("42", 10, Set.of(), null), 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).itemId()).isEqualTo("10");
        assertThat(results.get(1).itemId()).isEqualTo("20");
        assertThat(results.get(0).channel()).isEqualTo("embedding");
        // Scores must be descending (rank-based)
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void recall_emptyForUnknownUser() {
        CandidateGenerator cg = mock(CandidateGenerator.class);
        when(cg.byEmbedding(999, 5)).thenReturn(List.of());

        Channels.Embedding channel = new Channels.Embedding(cg);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("999", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }

    @Test
    void name_returnsEmbedding() {
        assertThat(new Channels.Embedding(mock(CandidateGenerator.class)).name()).isEqualTo("embedding");
    }
}
