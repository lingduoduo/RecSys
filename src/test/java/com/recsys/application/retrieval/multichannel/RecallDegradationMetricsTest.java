package com.recsys.application.retrieval.multichannel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

        RecallDegradationMetrics.Snapshot s = m.snapshot();
        assertThat(s.totalRecalls()).isEqualTo(2);
        assertThat(s.degradedRecalls()).isEqualTo(2);
        assertThat(s.byChannel().get("trending"))
                .containsEntry(RecallDegradationMetrics.Reason.REJECTED, 1L)
                .containsEntry(RecallDegradationMetrics.Reason.TIMEOUT, 1L);
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
}
