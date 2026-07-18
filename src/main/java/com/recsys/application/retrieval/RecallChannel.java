package com.recsys.application.retrieval;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;

import java.util.List;

public interface RecallChannel {
    String name();

    List<MovieCandidate> recall(RecommendationQuery query, int limit);

    default List<MovieCandidate> recallPrimary(RecommendationQuery query, int limit) {
        return recall(query, limit);
    }
}
