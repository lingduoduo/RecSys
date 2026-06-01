package com.recsys.recommendation.ranking;

import com.recsys.recommendation.retrieval.MovieCandidate;
import com.recsys.recommendation.ranking.RankedMovie;
import com.recsys.recommendation.retrieval.RecommendationQuery;

import java.util.List;

public interface CandidateRanker {
    List<RankedMovie> rank(RecommendationQuery query, List<MovieCandidate> candidates, int limit);
}
