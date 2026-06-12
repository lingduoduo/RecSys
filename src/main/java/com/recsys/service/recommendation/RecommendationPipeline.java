package com.recsys.service.recommendation;

import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;

public interface RecommendationPipeline {
    RecommendationResult recommend(RecommendationQuery query);
}
