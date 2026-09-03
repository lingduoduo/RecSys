package com.recsys.api.rest;

import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.domain.recommendation.RecommendationResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationV2ControllerTest {

    private static final RecommendationQuery QUERY = new RecommendationQuery("u1", 5, Set.of(), null);

    @Test
    void degradedCacheResultCarriesTheSameHeaderAsV1() {
        RecommendationResult degraded = new RecommendationResult("u1",
                List.of(new RankedMovie("7", 0.8, 1, Map.of())), null, false,
                Map.of("servedFrom", "degraded-cache", "abTestVariant", "training"));
        RecommendationV2Controller controller = new RecommendationV2Controller(q -> degraded);

        ResponseEntity<RecommendationResult> response = controller.recommend(QUERY);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-Served-From")).isEqualTo("degraded-cache");
        assertThat(response.getBody()).isSameAs(degraded);
    }

    @Test
    void ordinaryResultsCarryNoServedFromHeader() {
        RecommendationResult served = new RecommendationResult("u1", List.of(), null, false,
                Map.of("abTestVariant", "training"));
        RecommendationV2Controller controller = new RecommendationV2Controller(q -> served);

        ResponseEntity<RecommendationResult> response = controller.recommend(QUERY);

        assertThat(response.getHeaders().containsKey("X-Served-From")).isFalse();
    }
}
