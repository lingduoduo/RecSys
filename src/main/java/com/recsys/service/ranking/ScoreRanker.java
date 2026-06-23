package com.recsys.service.ranking;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class ScoreRanker implements CandidateRanker {
    @Override
    public List<RankedMovie> rank(RecommendationQuery query, List<MovieCandidate> candidates, int limit) {
        Objects.requireNonNull(query, "query");
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }

        AtomicInteger rank = new AtomicInteger(1);
        return candidates.stream()
                .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed()
                        .thenComparing(MovieCandidate::itemId))
                .limit(limit)
                .map(candidate -> new RankedMovie(
                        candidate.itemId(),
                        candidate.score(),
                        rank.getAndIncrement(),
                        candidate.features()
                ))
                .toList();
    }
}
