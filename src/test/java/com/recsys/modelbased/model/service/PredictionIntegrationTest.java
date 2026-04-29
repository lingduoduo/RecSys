package com.recsys.modelbased.model.service;

import com.recsys.modelbased.model.dto.RecommendRequest;
import com.recsys.modelbased.model.dto.RecommendResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end smoke test against the bundled classpath artifacts.
 * Exercises the full pipeline: ONNX inference → retrieval → ranking.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PredictionIntegrationTest {

    private RecommendationService service;
    private ModelRuntimeProvider runtimeProvider;

    @BeforeAll
    void setUp() throws Exception {
        var locator = new ModelArtifactLocator("", "");
        runtimeProvider = new ModelRuntimeProvider(locator, new com.recsys.modelbased.model.config.ABTestConfig());
        service = new RecommendationService(
                runtimeProvider,
                new ABTestService(new com.recsys.modelbased.model.config.ABTestConfig()));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (runtimeProvider != null) runtimeProvider.close();
    }

    @Test
    void recommend_knownUser_returnsNonEmptyRankedList() {
        RecommendResponse response = recommend("123", 5);

        assertThat(response.userId()).isEqualTo("123");
        assertThat(response.modelVersion()).isEqualTo("demo-model-ratings-v1");
        assertThat(response.recommendations()).isNotEmpty();
    }

    @Test
    void recommend_unknownUser_returnsValidResponse() {
        RecommendResponse response = recommend("unknown-user", 3);

        assertThat(response.userId()).isEqualTo("unknown-user");
        assertThat(response.recommendations()).isNotNull();
    }

    @Test
    void recommend_resultsRespectK() {
        RecommendResponse response = recommend("123", 3);

        assertThat(response.recommendations()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void recommend_scoresDescending() {
        List<Double> scores = recommend("123", 5).recommendations()
                .stream().map(item -> item.score()).toList();

        for (int i = 0; i + 1 < scores.size(); i++) {
            assertThat(scores.get(i)).isGreaterThanOrEqualTo(scores.get(i + 1));
        }
    }

    @Test
    void recommend_blankUserId_throwsIllegalArgument() {
        assertThatThrownBy(() -> recommend("", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void recommend_excludeItems_notInResults() {
        var req = new RecommendRequest();
        req.setUserId("123");
        req.setK(10);
        req.setExcludeItemIds(List.of("1", "2", "3"));

        var ids = service.recommend(req).recommendations()
                .stream().map(item -> item.itemId()).toList();
        assertThat(ids).doesNotContain("1", "2", "3");
    }

    private RecommendResponse recommend(String userId, int k) {
        var req = new RecommendRequest();
        req.setUserId(userId);
        req.setK(k);
        return service.recommend(req);
    }
}
