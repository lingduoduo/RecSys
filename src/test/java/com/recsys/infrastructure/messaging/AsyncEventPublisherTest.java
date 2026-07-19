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
}
