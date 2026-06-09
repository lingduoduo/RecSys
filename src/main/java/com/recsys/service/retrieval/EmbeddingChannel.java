package com.recsys.service.retrieval;

import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.domain.Movie;
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EmbeddingChannel implements RecallChannel {

    private final CandidateGenerator candidateGenerator;

    public EmbeddingChannel(CandidateGenerator candidateGenerator) {
        this.candidateGenerator = candidateGenerator;
    }

    @Override
    public String name() {
        return "embedding";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        int userId = Integer.parseInt(query.userId());
        List<Movie> movies = candidateGenerator.byEmbedding(userId, limit);
        List<MovieCandidate> candidates = new ArrayList<>(movies.size());
        // Rank-based score: 1.0 for rank-0, decaying by 1/(rank+1).
        // Preserves relative ordering from the vector index without exposing raw cosine values.
        for (int i = 0; i < movies.size(); i++) {
            double score = 1.0 / (i + 1.0);
            candidates.add(new MovieCandidate(String.valueOf(movies.get(i).id()), score, name(), Map.of()));
        }
        return candidates;
    }
}
