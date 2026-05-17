package com.recsys.streaming;

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
            assertThat(publisher.publish("{\"eventType\":\"impression\"}")).isTrue();
            assertThat(publisher.snapshot().published()).isEqualTo(1L);
        }
    }

    @Test
    void publish_dropsSilentlyWhenQueueFull() {
        // Capacity=1, batchSize=1000 so the drain thread won't flush quickly enough
        try (var publisher = new AsyncEventPublisher(1, 1_000)) {
            publisher.publish("first");  // fills the queue
            boolean accepted = publisher.publish("second"); // queue full → drop

            assertThat(accepted).isFalse();
            assertThat(publisher.snapshot().dropped()).isEqualTo(1L);
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
    void snapshot_reflectsPublishedAndDropped() {
        try (var publisher = new AsyncEventPublisher(2, 1_000)) {
            publisher.publish("e1");
            publisher.publish("e2");
            publisher.publish("e3"); // dropped

            var snap = publisher.snapshot();
            assertThat(snap.published()).isEqualTo(2L);
            assertThat(snap.dropped()).isEqualTo(1L);
        }
    }
}
