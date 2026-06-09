// src/test/java/com/recsys/service/retrieval/WorkerIsolationFailureTest.java
package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.streaming.FaultInjector;
import com.recsys.streaming.WorkerBulkhead;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerIsolationFailureTest {

    private WorkerBulkhead bulkhead;
    // 150ms timeout + thread pool scheduling + future completion overhead
    private static final long MAX_ISOLATION_ELAPSED_MS = 400L;

    @AfterEach
    void tearDown() {
        if (bulkhead != null) bulkhead.close();
    }

    @Test
    void slowChannelDoesNotBlockFastChannel() {
        bulkhead = new WorkerBulkhead("chaos-recall", 4, 16);
        FaultInjector faults = new FaultInjector();
        faults.injectLatency("channel:slow", 500);

        RecallChannel slow = namedChannel("slow",
                List.of(new MovieCandidate("slow-1", 0.95, "slow", Map.of())));
        RecallChannel fast = namedChannel("fast",
                List.of(new MovieCandidate("fast-1", 0.70, "fast", Map.of())));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(slow, fast), new ChannelHealthMonitor(),
                bulkhead.asExecutorService(), 150L, faults);

        long start = System.currentTimeMillis();
        List<MovieCandidate> results = service.recall(
                new RecommendationQuery("u1", 10, Set.of(), null), 10);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(results).hasSize(1);
        assertThat(results.get(0).itemId()).isEqualTo("fast-1");
        assertThat(elapsed).isLessThan(MAX_ISOLATION_ELAPSED_MS);
    }

    @Test
    void failingChannelEntersBackoff_andRecoversAfterFaultRemoved() {
        bulkhead = new WorkerBulkhead("chaos-health", 4, 16);
        AtomicLong clock = new AtomicLong(1_000_000L);
        // Low threshold (2 failures → backoff) and long backoff so it doesn't expire on its own
        ChannelHealthMonitor health = new ChannelHealthMonitor(2, 1_000L, 30_000L, clock::get);
        FaultInjector faults = new FaultInjector();
        faults.injectException("channel:failing", new RuntimeException("chaos!"));

        RecallChannel failing = namedChannel("failing",
                List.of(new MovieCandidate("x", 1.0, "failing", Map.of())));
        RecallChannel backup = namedChannel("backup",
                List.of(new MovieCandidate("b1", 0.5, "backup", Map.of())));

        MultiChannelRecallService service = new MultiChannelRecallService(
                List.of(failing, backup), health,
                bulkhead.asExecutorService(), 200L, faults);

        RecommendationQuery query = new RecommendationQuery("u1", 10, Set.of(), null);

        // Two failures trigger backoff (threshold = 2)
        service.recall(query, 10);
        service.recall(query, 10);
        assertThat(health.isAvailable("failing")).isFalse();

        // Advance clock past backoff (base = 1_000ms)
        clock.addAndGet(1_001L);
        assertThat(health.isAvailable("failing")).isTrue();

        // Remove fault → recovery probe succeeds
        faults.clear("channel:failing");
        List<MovieCandidate> recovered = service.recall(query, 10);

        assertThat(health.snapshot().get("failing").consecutiveFailures()).isZero();
        assertThat(recovered.stream().map(MovieCandidate::itemId).toList()).contains("x");
    }

    private static RecallChannel namedChannel(String name, List<MovieCandidate> results) {
        return new RecallChannel() {
            @Override public String name() { return name; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                return results;
            }
        };
    }
}
