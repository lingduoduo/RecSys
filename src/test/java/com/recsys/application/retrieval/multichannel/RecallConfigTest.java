package com.recsys.application.retrieval.multichannel;
import com.recsys.application.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.application.retrieval.multichannel.RecallConfig;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.resilience.FaultInjector;
import com.recsys.application.retrieval.RecallChannel;
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

    @Test
    void defaultChannelTimeoutIs200WhenEnvUnset() {
        RecallConfig config = RecallConfig.builder()
                .channels(List.of(channel("c", new MovieCandidate("1", 1.0, "c", Map.of()))))
                .executor(ForkJoinPool.commonPool())
                .build();
        assertThat(config.channelTimeoutMs()).isEqualTo(200L);
    }

    @Test
    void readLongEnvReturnsSuppliedDefaultWhenVarUnset() {
        // RECALL_CHANNEL_TIMEOUT_MS is not set in the test environment; the helper returns the default.
        assertThat(RecallConfig.readLongEnv("RECALL_CHANNEL_TIMEOUT_MS", 200L)).isEqualTo(200L);
        assertThat(RecallConfig.readLongEnv("RECALL_CHANNEL_TIMEOUT_MS", 1500L)).isEqualTo(1500L);
    }

    @Test
    void parseLongOrDefault_parsesValidAndTrims() {
        // The parse seam behind readLongEnv: the env-set override is unreachable in-process
        // (Java env is immutable at runtime), but the parse/trim logic is directly testable.
        assertThat(RecallConfig.parseLongOrDefault("1500", 200L)).isEqualTo(1500L);
        assertThat(RecallConfig.parseLongOrDefault("  7  ", 200L)).isEqualTo(7L);
    }

    @Test
    void parseLongOrDefault_fallsBackOnNullBlankOrGarbage() {
        assertThat(RecallConfig.parseLongOrDefault(null, 200L)).isEqualTo(200L);
        assertThat(RecallConfig.parseLongOrDefault("", 200L)).isEqualTo(200L);
        assertThat(RecallConfig.parseLongOrDefault("   ", 200L)).isEqualTo(200L);
        assertThat(RecallConfig.parseLongOrDefault("abc", 200L)).isEqualTo(200L);
    }
}
