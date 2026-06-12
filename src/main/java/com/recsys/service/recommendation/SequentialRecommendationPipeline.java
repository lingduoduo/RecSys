package com.recsys.service.recommendation;

import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;

public final class SequentialRecommendationPipeline implements RecommendationPipeline {

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        throw new UnsupportedOperationException(
                "Sequential/LLM recommendation is not yet implemented. " +
                "Future: SASRec / BERT4Rec / LLM-based path.");
    }
}
