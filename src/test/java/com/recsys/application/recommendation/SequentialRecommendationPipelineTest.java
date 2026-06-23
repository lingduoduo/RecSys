package com.recsys.application.recommendation;
import com.recsys.application.recommendation.SequentialRecommendationPipeline;
import com.recsys.application.recommendation.RecommendationPipeline;

import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.exception.PipelineNotImplementedException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SequentialRecommendationPipelineTest {

    @Test
    void recommend_throwsPipelineNotImplementedException() {
        RecommendationPipeline pipeline = new SequentialRecommendationPipeline();
        assertThatThrownBy(() -> pipeline.recommend(
                new RecommendationQuery("u1", 5, Set.of(), null)))
                .isInstanceOf(PipelineNotImplementedException.class)
                .hasMessageContaining("not yet implemented");
    }
}
