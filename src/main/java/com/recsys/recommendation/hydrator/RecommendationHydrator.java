package com.recsys.recommendation.hydrator;

import com.recsys.recommendation.ranking.RankedMovie;
import com.recsys.recommendation.retrieval.RecommendationQuery;

import java.util.List;

public interface RecommendationHydrator {
    RecommendationHydrator IDENTITY = (query, rankedMovies) ->
            rankedMovies == null || rankedMovies.isEmpty() ? List.of() : List.copyOf(rankedMovies);

    List<RankedMovie> hydrate(RecommendationQuery query, List<RankedMovie> rankedMovies);
}
