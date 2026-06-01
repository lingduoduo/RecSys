package com.recsys.recommendation.service.ranking;

import com.recsys.recommendation.model.MovieCandidate;
import com.recsys.recommendation.model.RankedMovie;
import com.recsys.recommendation.model.RecommendationQuery;

import java.util.List;

public interface CandidateRanker {
    List<RankedMovie> rank(RecommendationQuery query, List<MovieCandidate> candidates, int limit);
}
