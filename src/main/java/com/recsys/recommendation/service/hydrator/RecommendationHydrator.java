package com.recsys.recommendation.service.hydrator;

import com.recsys.recommendation.model.RankedMovie;
import com.recsys.recommendation.model.RecommendationQuery;

import java.util.List;

public interface RecommendationHydrator {
    RecommendationHydrator IDENTITY = (query, rankedMovies) ->
            rankedMovies == null || rankedMovies.isEmpty() ? List.of() : List.copyOf(rankedMovies);

    List<RankedMovie> hydrate(RecommendationQuery query, List<RankedMovie> rankedMovies);
}
