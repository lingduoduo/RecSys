package com.recsys.recommendation.service.retrieval;

import com.recsys.recommendation.model.MovieCandidate;
import com.recsys.recommendation.model.RecommendationQuery;

import java.util.List;

public interface RecallChannel {
    String name();

    List<MovieCandidate> recall(RecommendationQuery query, int limit);
}
