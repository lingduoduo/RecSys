package com.recsys.application.retrieval.multichannel;

import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static com.recsys.application.retrieval.multichannel.RecallResult.DegradationOutcome.*;

class RecallDegradationMetricsTest {

    @Test
    void classifyMapsThrowableTypesAndUnwrapsCompletionException() {
        assertThat(RecallDegradationMetrics.classify(new RejectedExecutionException()))
                .isEqualTo(RecallDegradationMetrics.Reason.REJECTED);
        assertThat(RecallDegradationMetrics.classify(new TimeoutException()))
                .isEqualTo(RecallDegradationMetrics.Reason.TIMEOUT);
        assertThat(RecallDegradationMetrics.classify(new CompletionException(new TimeoutException())))
                .isEqualTo(RecallDegradationMetrics.Reason.TIMEOUT);
        assertThat(RecallDegradationMetrics.classify(new IllegalStateException("boom")))
                .isEqualTo(RecallDegradationMetrics.Reason.ERROR);
    }

    @Test
    void snapshotCountsAndRatio() {
        RecallDegradationMetrics m = new RecallDegradationMetrics();
        m.recordTotal();
        m.recordTotal();
        m.record("trending", RecallDegradationMetrics.Reason.REJECTED);
        m.record("trending", RecallDegradationMetrics.Reason.TIMEOUT);
        // One request degraded (even though it hit the "trending" channel twice above via two
        // separate record() calls in this test) — recordDegradedRequest() is the caller's
        // once-per-request signal, independent of how many per-channel counters were bumped.
        m.recordDegradedRequest();

        RecallDegradationMetrics.Snapshot s = m.snapshot();
        assertThat(s.totalRecalls()).isEqualTo(2);
        assertThat(s.degradedRecalls()).isEqualTo(1);
        assertThat(s.byChannel().get("trending"))
                .containsEntry(RecallDegradationMetrics.Reason.REJECTED, 1L)
                .containsEntry(RecallDegradationMetrics.Reason.TIMEOUT, 1L);
        assertThat(s.degradedRatio()).isEqualTo(0.5, within(1e-9));
    }

    @Test
    void recordDegradedRequestCountsOncePerRequestNotPerChannel() {
        // Regression for the bug where degradedRecalls tracked per-channel record() calls
        // instead of per-request degradation, letting degradedRatio exceed 1.0.
        RecallDegradationMetrics m = new RecallDegradationMetrics();
        m.recordTotal();
        m.record("trending", RecallDegradationMetrics.Reason.REJECTED);
        m.record("popularity", RecallDegradationMetrics.Reason.TIMEOUT);
        m.recordDegradedRequest();

        RecallDegradationMetrics.Snapshot s = m.snapshot();
        assertThat(s.totalRecalls()).isEqualTo(1);
        assertThat(s.degradedRecalls()).isEqualTo(1);
        assertThat(s.degradedRatio()).isLessThanOrEqualTo(1.0);
        assertThat(s.degradedRatio()).isEqualTo(1.0, within(1e-9));
    }

    @Test
    void zeroTrafficRatioIsZeroNotNaN() {
        assertThat(new RecallDegradationMetrics().snapshot().degradedRatio())
                .isEqualTo(0.0);
    }

    @Test
    void concurrentRecordsAreCounted() throws InterruptedException {
        RecallDegradationMetrics m = new RecallDegradationMetrics();
        int threads = 8, perThread = 1000;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    m.recordTotal();
                    m.record("c", RecallDegradationMetrics.Reason.REJECTED);
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        RecallDegradationMetrics.Snapshot s = m.snapshot();
        assertThat(s.totalRecalls()).isEqualTo((long) threads * perThread);
        assertThat(s.byChannel().get("c").get(RecallDegradationMetrics.Reason.REJECTED))
                .isEqualTo((long) threads * perThread);
    }

    @Test
    void snapshotCountsEachBoundedOutcome() {
        RecallDegradationMetrics m = new RecallDegradationMetrics();
        m.recordOutcome(HEALTHY);
        m.recordOutcome(PARTIAL);
        m.recordOutcome(ALL_CHANNELS);
        m.recordOutcome(FALLBACK);

        assertThat(m.snapshot().byOutcome())
                .containsEntry(HEALTHY, 1L)
                .containsEntry(PARTIAL, 1L)
                .containsEntry(ALL_CHANNELS, 1L)
                .containsEntry(FALLBACK, 1L);
    }

    @Test
    void micrometerUsesOnlyBoundedOutcomeTags() {
        RecallDegradationMetrics m = new RecallDegradationMetrics();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        m.registerMetrics(registry);
        m.record("unbounded-channel-name", RecallDegradationMetrics.Reason.ERROR);
        m.recordOutcome(PARTIAL);

        assertThat(registry.getMeters()).hasSize(4);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(io.micrometer.core.instrument.Tag::getKey)
                        .containsExactly("outcome"));
        assertThat(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getValue))
                .doesNotContain("unbounded-channel-name");
        assertThat(registry.get("recsys.recall.degradation.outcomes")
                .tag("outcome", "partial").functionCounter().count()).isEqualTo(1.0);
    }
}
