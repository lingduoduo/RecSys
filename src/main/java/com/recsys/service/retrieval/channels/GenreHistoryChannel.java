package com.recsys.service.retrieval.channels;

import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.service.retrieval.RecallChannel;

import java.util.List;
import java.util.Map;

public class GenreHistoryChannel implements RecallChannel {

    static final double SCORE = 0.5;

    private final CandidateGenerator candidateGenerator;

    public GenreHistoryChannel(CandidateGenerator candidateGenerator) {
        this.candidateGenerator = candidateGenerator;
    }

    @Override
    public String name() {
        return "genre_history";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        int userId = Integer.parseInt(query.userId());
        List<Movie> movies = candidateGenerator.byUserHistory(userId, limit);
        return movies.stream()
                .map(m -> new MovieCandidate(String.valueOf(m.id()), SCORE, name(), Map.of()))
                .toList();
    }
}
