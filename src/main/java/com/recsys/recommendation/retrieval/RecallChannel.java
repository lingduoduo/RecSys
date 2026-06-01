package com.recsys.recommendation.retrieval;

import com.recsys.recommendation.retrieval.MovieCandidate;
import com.recsys.recommendation.retrieval.RecommendationQuery;

import java.util.List;

public interface RecallChannel {
    String name();

    List<MovieCandidate> recall(RecommendationQuery query, int limit);
}
