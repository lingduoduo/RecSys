package com.recsys.service.ranking;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;

import java.util.List;

public interface CandidateRanker {
    List<RankedMovie> rank(RecommendationQuery query, List<MovieCandidate> candidates, int limit);
}
