package com.recsys.application.recommendation;

import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import com.recsys.exception.PipelineNotImplementedException;

public final class SequentialRecommendationPipeline implements RecommendationPipeline {

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        throw new PipelineNotImplementedException(
                "Sequential/LLM recommendation is not yet implemented. " +
                "Future: SASRec / BERT4Rec / LLM-based path.");
    }
}
