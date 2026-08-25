package com.recsys.infrastructure.messaging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncEventPublisherTest {

    @Test
    void publish_queuesEventAndReturnsTrue() {
        try (var publisher = new AsyncEventPublisher(100, 10)) {
            assertThat(publisher.publish("{\"eventType\":\"click\"}")).isTrue();
            assertThat(publisher.snapshot().published()).isEqualTo(1L);
        }
    }

    @Test
    void publish_acceptsKafkaEventEnvelopeValue() {
        try (var publisher = new AsyncEventPublisher(100, 10)) {
            var event = new LogCollector.KafkaEvent(
                    "movie_events",
                    "user:123",
                    "{\"eventType\":\"click\"}",
                    java.util.Map.of("eventType", "click"));

            assertThat(publisher.publish(event)).isTrue();
            assertThat(publisher.snapshot().published()).isEqualTo(1L);
        }
    }

    @Test
    void publish_withKeyPreservesEnvelopeAndIncrementsPublished() throws InterruptedException {
        CountDownLatch drained = new CountDownLatch(1);
        List<AsyncEventPublisher.EventEnvelope> received = new ArrayList<>();
        AsyncEventPublisher publisher = new AsyncEventPublisher(100, 10) {
            @Override
            protected void sendEnvelopes(List<EventEnvelope> events) {
                received.addAll(events);
                super.sendEnvelopes(events);
                drained.countDown();
            }
        };

        try {
            String json = "{\"eventType\":\"click\"}";
            assertThat(publisher.publish("42", json)).isTrue();
            assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(publisher.snapshot().published()).isEqualTo(1L);
            assertThat(received).containsExactly(new AsyncEventPublisher.EventEnvelope("42", json));
        } finally {
            publisher.close();
        }
    }

    @Test
    void publish_dropsSilentlyWhenQueueFull() throws InterruptedException {
        CountDownLatch drainStarted = new CountDownLatch(1);
        CountDownLatch releaseDrain = new CountDownLatch(1);
        AsyncEventPublisher publisher = new AsyncEventPublisher(1, 1) {
            @Override
            protected void sendBatch(List<String> events) {
                drainStarted.countDown();
                try {
                    releaseDrain.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.sendBatch(events);
            }
        };

        try {
            assertThat(publisher.publish("first")).isTrue();
            assertThat(drainStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(publisher.publish("second")).isTrue(); // fills queue while drain is blocked
            boolean accepted = publisher.publish("third"); // queue full -> drop
            assertThat(accepted).isFalse();
            assertThat(publisher.snapshot().dropped()).isEqualTo(1L);
        } finally {
            releaseDrain.countDown();
            publisher.close();
        }
    }

    @Test
    void drainThread_flushesQueuedEvents() throws InterruptedException {
        CountDownLatch drained = new CountDownLatch(3);
        List<String> received = new ArrayList<>();

        AsyncEventPublisher publisher = new AsyncEventPublisher(100, 10) {
            @Override
            protected void sendBatch(List<String> events) {
                super.sendBatch(events);
                received.addAll(events);
                events.forEach(e -> drained.countDown());
            }
        };

        publisher.publish("a");
        publisher.publish("b");
        publisher.publish("c");

        assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(publisher.snapshot().drained()).isEqualTo(3L);
        publisher.close();
    }

    @Test
    void close_drainsRemainingEventsBeforeShutdown() {
        List<String> flushed = new ArrayList<>();
        AsyncEventPublisher publisher = new AsyncEventPublisher(100, 1_000) {
            @Override
            protected void sendBatch(List<String> events) {
                super.sendBatch(events);
                flushed.addAll(events);
            }
        };

        publisher.publish("x");
        publisher.publish("y");
        publisher.close();

        // close() drains remaining events synchronously before returning
        assertThat(flushed).contains("x", "y");
    }

    @Test
    void snapshot_reflectsPublishedAndDropped() throws Exception {
        CountDownLatch drainStarted = new CountDownLatch(1);
        CountDownLatch releaseDrain = new CountDownLatch(1);
        AsyncEventPublisher publisher = new AsyncEventPublisher(1, 1) {
            @Override
            protected void sendBatch(List<String> events) {
                drainStarted.countDown();
                try {
                    releaseDrain.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.sendBatch(events);
            }
        };

        try {
            assertThat(publisher.publish("e1")).isTrue();
            assertThat(drainStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(publisher.publish("e2")).isTrue();
            assertThat(publisher.publish("e3")).isFalse();

            var snap = publisher.snapshot();
            assertThat(snap.published()).isEqualTo(2L);
            assertThat(snap.dropped()).isEqualTo(1L);
        } finally {
            releaseDrain.countDown();
            publisher.close();
        }
    }

    @Test void closedPublisherCountsEveryRejectedNonNullEvent() {
        AsyncEventPublisher publisher = new AsyncEventPublisher(1, 1);
        publisher.close();
        assertThat(publisher.publish("one")).isFalse();
        assertThat(publisher.publish("two")).isFalse();
        assertThat(publisher.publish((String) null)).isFalse();
        assertThat(publisher.snapshot().dropped()).isEqualTo(2);
    }

    @Test
    void reportsTheEffectiveCapacityItWasConstructedWith() {
        AsyncEventPublisher publisher = new AsyncEventPublisher(4, 1);
        try {
            assertThat(publisher.capacity()).isEqualTo(4);
            assertThat(publisher.snapshot().queueCapacity()).isEqualTo(4);
        } finally {
            publisher.close();
        }
    }

    @Test
    void aNonPositiveRequestedCapacityIsClampedToOneAndReportedAsSuch() {
        AsyncEventPublisher publisher = new AsyncEventPublisher(0, 1);
        try {
            assertThat(publisher.capacity()).isEqualTo(1);
        } finally {
            publisher.close();
        }
    }

    /**
     * publish() opens with `if (!running) return recordRejectedEvent()`, so a closed publisher
     * counted refusals identically to a full queue. Counting both as saturation would fire the
     * queue alert on every rolling deploy.
     */
    @Test
    void rejectionsAfterCloseAreShutdownNotFull() {
        AsyncEventPublisher publisher = new AsyncEventPublisher(1000, 1000) {
            @Override protected void sendBatch(java.util.List<String> events) { /* swallow */ }
        };
        publisher.close();

        publisher.publish("late-event");

        assertThat(publisher.rejected(com.recsys.metrics.QueueMetrics.RejectionReason.SHUTDOWN)).isEqualTo(1L);
        assertThat(publisher.rejected(com.recsys.metrics.QueueMetrics.RejectionReason.FULL))
                .as("a shutdown refusal must not inflate the saturation counter")
                .isZero();
    }

    /**
     * droppedCount is the pre-existing all-reasons total (async_events_dropped_total reads it
     * unchanged); FULL is derived by subtracting the other two reasons from it rather than
     * counted on its own. This is the one place a future fourth reason would silently corrupt
     * the FULL figure without this arithmetic check catching it.
     */
    @Test
    void theThreeRejectionReasonsSumToDroppedCount() {
        AsyncEventPublisher publisher = new AsyncEventPublisher(1, 1000) {
            @Override protected void sendBatch(java.util.List<String> events) { /* swallow */ }
        };
        try {
            // Fill the single-slot queue so the next publish is rejected as FULL. The drain
            // thread isn't paused, so this races the drain loop; retry publishing until at least
            // one FULL rejection lands rather than depending on timing.
            for (int i = 0; i < 10_000
                    && publisher.rejected(com.recsys.metrics.QueueMetrics.RejectionReason.FULL) == 0; i++) {
                publisher.publish("filler-" + i);
            }
            assertThat(publisher.rejected(com.recsys.metrics.QueueMetrics.RejectionReason.FULL))
                    .as("expected at least one FULL rejection to set up this assertion")
                    .isGreaterThan(0L);
        } finally {
            publisher.close();
        }
        publisher.publish("after-close");

        long full = publisher.rejected(com.recsys.metrics.QueueMetrics.RejectionReason.FULL);
        long shutdown = publisher.rejected(com.recsys.metrics.QueueMetrics.RejectionReason.SHUTDOWN);
        long invalidKey = publisher.rejected(com.recsys.metrics.QueueMetrics.RejectionReason.INVALID_KEY);
        assertThat(full + shutdown + invalidKey).isEqualTo(publisher.snapshot().dropped());
    }
}
