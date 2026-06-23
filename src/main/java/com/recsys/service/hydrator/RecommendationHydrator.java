package com.recsys.service.hydrator;

import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;

import java.util.List;

public interface RecommendationHydrator {
    RecommendationHydrator IDENTITY = (query, rankedMovies) ->
            rankedMovies == null || rankedMovies.isEmpty() ? List.of() : List.copyOf(rankedMovies);

    List<RankedMovie> hydrate(RecommendationQuery query, List<RankedMovie> rankedMovies);
}
