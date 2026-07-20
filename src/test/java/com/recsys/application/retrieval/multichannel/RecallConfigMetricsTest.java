package com.recsys.application.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.application.retrieval.RecallChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RecallConfigMetricsTest {

    private static final class OkChannel implements RecallChannel {
        @Override public String name() { return "ok"; }
        @Override public List<MovieCandidate> recall(RecommendationQuery q, int limit) {
            return List.of(new MovieCandidate("1", 0.5, "ok", java.util.Map.of()));
        }
        @Override public List<MovieCandidate> recallPrimary(RecommendationQuery q, int limit) { return recall(q, limit); }
    }

    @Test
    void builderDefaultsToNonNullMetrics() {
        RecallConfig config = RecallConfig.builder()
                .channels(List.of(new OkChannel()))
                .executor(Executors.newSingleThreadExecutor())
                .build();
        assertThat(config.recallMetrics()).isNotNull();
    }

    @Test
    void builderCarriesSuppliedMetricsInstance() {
        RecallDegradationMetrics metrics = new RecallDegradationMetrics();
        RecallConfig config = RecallConfig.builder()
                .channels(List.of(new OkChannel()))
                .executor(Executors.newSingleThreadExecutor())
                .recallMetrics(metrics)
                .build();
        assertThat(config.recallMetrics()).isSameAs(metrics);
    }
}
