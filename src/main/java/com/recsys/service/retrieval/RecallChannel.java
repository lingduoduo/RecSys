package com.recsys.service.retrieval;

import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;

import java.util.List;

public interface RecallChannel {
    String name();

    List<MovieCandidate> recall(RecommendationQuery query, int limit);
}
