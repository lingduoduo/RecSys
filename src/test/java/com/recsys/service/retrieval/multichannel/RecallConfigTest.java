package com.recsys.service.retrieval.multichannel;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.online.ops.FaultInjector;
import com.recsys.service.retrieval.RecallChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecallConfigTest {

    private static RecallChannel channel(String name, MovieCandidate... candidates) {
        return new RecallChannel() {
            @Override public String name() { return name; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                return List.of(candidates);
            }
        };
    }

    @Test
    void builderRejectsEmptyChannels() {
        assertThatThrownBy(() -> RecallConfig.builder()
                .channels(List.of())
                .executor(ForkJoinPool.commonPool())
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderRejectsNullExecutor() {
        assertThatThrownBy(() -> RecallConfig.builder()
                .channels(List.of(channel("c", new MovieCandidate("1", 1.0, "c", Map.of()))))
                .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromConfig_buildsWorkingService_withDefaults() {
        RecallChannel c = channel("c",
                new MovieCandidate("1", 0.9, "c", Map.of()),
                new MovieCandidate("2", 0.8, "c", Map.of()));

        MultiChannelRecallService service = MultiChannelRecallService.from(
                RecallConfig.builder()
                        .channels(List.of(c))
                        .executor(ForkJoinPool.commonPool())
                        .build());

        // No userEmbeddingStore set -> legacy (non-quota) merge path; both candidates returned.
        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);
        assertThat(recalled).extracting(MovieCandidate::itemId).containsExactlyInAnyOrder("1", "2");
    }
}
