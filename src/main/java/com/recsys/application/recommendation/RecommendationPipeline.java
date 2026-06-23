package com.recsys.application.recommendation;

import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;

public interface RecommendationPipeline {
    RecommendationResult recommend(RecommendationQuery query);
}
