package com.recsys.infrastructure.redis;

import com.recsys.application.retrieval.RecallChannel;
import com.recsys.application.retrieval.multichannel.RecallConfig;
import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recall channel timeout bounds the caller's wait; the Redis command timeout bounds the
 * <em>worker</em>. Both are needed, and only one of them has a safe default.
 *
 * <p>Each channel is dispatched as {@code supplyAsync(channel, recallBulkhead).orTimeout(200ms)}.
 * {@code orTimeout} completes the dependent future exceptionally — it neither cancels nor
 * interrupts the task already running on the bulkhead, and a task blocked in a socket read would
 * not observe an interrupt anyway. Measured with a 1-thread pool and a 2000 ms task: the caller
 * degraded at 220 ms, and the next task waited 1790 ms for a worker. So a stalled Redis holds a
 * bulkhead thread for the full <em>command</em> timeout regardless of the channel timeout.
 *
 * <p>{@link LettuceClientFactory#DEFAULT_TIMEOUT_MS} is 2000 ms, 10x the channel default, so a
 * service that leaves {@code REDIS_TIMEOUT_MS} unset drains its bulkhead 10x slower than the
 * budget above it implies. Three services run recall and each bounds this differently:
 * model-serving caps it in code ({@code ModelRuntimeProvider.RECALL_REDIS_TIMEOUT_MS} = 150),
 * while online-serving and catalog-serving depend on manifest env. Catalog-serving had neither
 * until 2026-08-17 — the same hazard mitigated twice and missed once, which is the shape of
 * defect a two-directional manifest assertion catches and a code review does not.
 *
 * <p>Scope limit: this reads manifest text. It proves a coupling between two files, not what a
 * cluster receives, and it says nothing about a deployment that does not apply {@code k8s/base}
 * — {@code scripts/run-microservices-local.sh} still gets the 2000 ms code default. See
 * {@code docs/system_design/23_Online_Serving_Latency.md} §2 and sharp edge 1.
 */
class CatalogRedisTimeoutManifestTest {

    /** Every service whose request path runs multi-channel recall against Redis. */
    private static final List<Path> RECALL_SERVICE_MANIFESTS = List.of(
            Path.of("k8s", "base", "catalog-serving.yaml"),
            Path.of("k8s", "base", "online-serving.yaml"));

    /**
     * The channel timeout as the code actually resolves it, rather than a literal repeated from
     * the manifest. A change to {@code RecallConfig}'s default re-tightens this bound instead of
     * silently invalidating it.
     */
    private static long channelTimeoutDefaultMs() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return RecallConfig.builder()
                    .channels(List.of(new NoopChannel()))
                    .executor(executor)
                    .build()
                    .channelTimeoutMs();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void everyRecallServicePinsARedisCommandTimeoutInsideItsChannelBudget() throws IOException {
        long channelTimeoutMs = channelTimeoutDefaultMs();

        for (Path manifest : RECALL_SERVICE_MANIFESTS) {
            assertThat(manifest).exists();

            Optional<Long> configured = redisTimeoutMs(manifest);
            assertThat(configured)
                    .as("%s runs multi-channel recall, so it must pin REDIS_TIMEOUT_MS. Unset "
                            + "means %dms (LettuceClientFactory.DEFAULT_TIMEOUT_MS), and a "
                            + "timed-out channel then holds its bulkhead thread that long",
                            manifest, LettuceClientFactory.DEFAULT_TIMEOUT_MS)
                    .isPresent();

            assertThat(configured.get())
                    .as("%s sets REDIS_TIMEOUT_MS above the %dms recall channel budget, so a "
                            + "stalled Redis outlives the caller that gave up on it",
                            manifest, channelTimeoutMs)
                    .isLessThanOrEqualTo(channelTimeoutMs);
        }
    }

    /**
     * The premise the assertion above rests on. If the Lettuce default were already inside the
     * channel budget, pinning it in every manifest would be redundant ceremony.
     */
    @Test
    void theLettuceDefaultIsWhatMakesPinningNecessary() {
        assertThat((long) LettuceClientFactory.DEFAULT_TIMEOUT_MS)
                .as("the unset default must exceed the channel budget, or this test has no subject")
                .isGreaterThan(channelTimeoutDefaultMs());
    }

    /**
     * Reads the value of the {@code REDIS_TIMEOUT_MS} container env entry. Comment lines are
     * skipped so prose naming the variable cannot satisfy the assertion — the manifest explains
     * this setting at length directly above it.
     */
    private static Optional<Long> redisTimeoutMs(Path manifest) throws IOException {
        List<String> lines = Files.readAllLines(manifest).stream()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .toList();

        for (int i = 0; i < lines.size() - 1; i++) {
            if (!lines.get(i).equals("- name: REDIS_TIMEOUT_MS")) continue;
            String next = lines.get(i + 1);
            if (!next.startsWith("value:")) continue;
            String raw = next.substring("value:".length()).strip().replace("\"", "").replace("'", "");
            try {
                return Optional.of(Long.parseLong(raw));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** RecallConfig.build() requires at least one channel; none of them is ever invoked here. */
    private static final class NoopChannel implements RecallChannel {
        @Override public String name() { return "noop"; }
        @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
            return List.of();
        }
    }
}
