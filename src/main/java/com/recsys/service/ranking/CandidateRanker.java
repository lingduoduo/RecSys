package com.recsys.service.ranking;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;

import java.util.List;

public interface CandidateRanker {
    List<RankedMovie> rank(RecommendationQuery query, List<MovieCandidate> candidates, int limit);
}
