package com.recsys.service.recommendation;

import com.recsys.domain.RecommendationQuery;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SequentialRecommendationPipelineTest {

    @Test
    void recommend_throwsUnsupportedOperationException() {
        RecommendationPipeline pipeline = new SequentialRecommendationPipeline();
        assertThatThrownBy(() -> pipeline.recommend(
                new RecommendationQuery("u1", 5, Set.of(), null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not yet implemented");
    }
}
