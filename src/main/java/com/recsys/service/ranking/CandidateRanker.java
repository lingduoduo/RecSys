package com.recsys.service.ranking;

import com.recsys.model.MovieCandidate;
import com.recsys.model.RankedMovie;
import com.recsys.model.RecommendationQuery;

import java.util.List;

public interface CandidateRanker {
    List<RankedMovie> rank(RecommendationQuery query, List<MovieCandidate> candidates, int limit);
}
