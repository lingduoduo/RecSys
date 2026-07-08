package com.recsys.application.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;
import com.recsys.domain.recommendation.RecommendationQuery;
import com.recsys.resilience.FaultInjector;
import com.recsys.resilience.WorkerBulkhead;
import com.recsys.application.retrieval.RecallChannel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MultiChannelRecallServiceBulkheadTest {

    @Test
    void rejectedChannelDegradesGracefullyInsteadOfThrowing() {
        // Bulkhead with 1 worker + queue 1 -> at most 2 in-flight; extra channels are rejected.
        WorkerBulkhead bulkhead = new WorkerBulkhead("test-recall", 1, 1);
        ChannelHealthMonitor health = new ChannelHealthMonitor();

        // 4 slow channels: with poolSize=1 + queueCapacity=1, at most 2 can be accepted
        // (1 running + 1 queued); the remaining channels must be rejected synchronously.
        RecallChannel ch1 = slowChannel("ch1");
        RecallChannel ch2 = slowChannel("ch2");
        RecallChannel ch3 = slowChannel("ch3");
        RecallChannel ch4 = slowChannel("ch4");

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(ch1, ch2, ch3, ch4),
                health,
                bulkhead.asExecutorService(),
                5_000L,
                FaultInjector.NOOP);

        RecommendationQuery query = new RecommendationQuery("u1", 10, Set.of(), null);

        assertThatCode(() -> {
            List<MovieCandidate> result = service.recall(query, 10);
            assertThat(result).isNotNull(); // request completed, did not throw
        }).doesNotThrowAnyException();

        // At least one channel must have been rejected by the bulkhead and recorded a failure.
        boolean anyChannelUnhealthy =
                health.snapshot().values().stream().anyMatch(s -> s.consecutiveFailures() > 0);
        assertThat(anyChannelUnhealthy).isTrue();

        bulkhead.close();
    }

    private static RecallChannel slowChannel(String name) {
        return new RecallChannel() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of(new MovieCandidate(name + "-1", 0.5, name, Map.of()));
            }
        };
    }
}
