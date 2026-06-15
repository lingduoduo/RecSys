package com.recsys.service.retrieval.channels;

import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenreHistoryChannelTest {

    @Test
    void recall_returnsGenreBasedCandidates() {
        CandidateGenerator cg = mock(CandidateGenerator.class);
        Movie m = new Movie(5, "Inception", 2010, List.of("Sci-Fi"));
        when(cg.byUserHistory(7, 10)).thenReturn(List.of(m));

        GenreHistoryChannel channel = new GenreHistoryChannel(cg);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("7", 10, Set.of(), null), 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).itemId()).isEqualTo("5");
        assertThat(results.get(0).score()).isEqualTo(0.5);
        assertThat(results.get(0).channel()).isEqualTo("genre_history");
    }

    @Test
    void recall_emptyForUserWithNoHistory() {
        CandidateGenerator cg = mock(CandidateGenerator.class);
        when(cg.byUserHistory(999, 5)).thenReturn(List.of());

        List<MovieCandidate> results = new GenreHistoryChannel(cg).recall(
                new RecommendationQuery("999", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }

    @Test
    void name_returnsGenreHistory() {
        assertThat(new GenreHistoryChannel(mock(CandidateGenerator.class)).name())
                .isEqualTo("genre_history");
    }
}
