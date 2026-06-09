package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;

import java.util.List;

public interface RecallChannel {
    String name();

    List<MovieCandidate> recall(RecommendationQuery query, int limit);
}
