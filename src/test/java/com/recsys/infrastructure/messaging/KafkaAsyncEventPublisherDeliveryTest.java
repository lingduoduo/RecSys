package com.recsys.infrastructure.messaging;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three {@code KafkaAsyncEventPublisher} tests that wait on the drain thread.
 *
 * <p>Split out of {@link KafkaAsyncEventPublisherTest} so that class can sit in the merge-blocking
 * {@code resilience} profile, which is documented timing-free and was burned once by a racy test
 * (issue #261). These three assert on what a background daemon thread has delivered to a
 * {@code MockProducer}, so they need a bounded wait no matter how it is written. The bound fails
 * loudly rather than hanging, but a wait is a wait, and this gate is the wrong place for one.
 *
 * <p>Same split already applied to {@code GcEventTrackerTest} / {@code GcEventTrackerLifecycleTest}
 * and {@code JvmMetricsBinderTest} / {@code JvmMetricsBinderGcObservationTest}.
 */
class KafkaAsyncEventPublisherDeliveryTest {

    @Test
    void publish_usesExtractedUserIdAsKafkaKey() {
        MockProducer<String, String> producer =
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaAsyncEventPublisher publisher = new KafkaAsyncEventPublisher(
                producer, "movie_events", 100, 10, MovieEventKafkaKeyExtractor::extract);

        try {
            assertThat(publisher.publish("{\"userId\":42,\"eventId\":\"e-1\"}")).isTrue();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(producer.history()).singleElement()
                            .satisfies(record -> assertThat(record.key()).isEqualTo("42")));
        } finally {
            publisher.close();
        }
    }
    @Test
    void publish_withLegacyConstructorAcceptsAndDeliversNullKey() {
        MockProducer<String, String> producer =
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaAsyncEventPublisher publisher =
                new KafkaAsyncEventPublisher(producer, "ab_exposures", 100, 10);

        try {
            assertThat(publisher.publish("{\"eventId\":\"e-1\"}")).isTrue();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(producer.history()).singleElement()
                            .satisfies(record -> assertThat(record.key()).isNull()));
        } finally {
            publisher.close();
        }
    }
    @Test
    void publish_kafkaEventUsesDynamicDispatchAndDeliversNullKey() {
        MockProducer<String, String> producer =
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaAsyncEventPublisher publisher =
                new KafkaAsyncEventPublisher(producer, "ab_exposures", 100, 10);
        LogCollector.KafkaEvent event = new LogCollector.KafkaEvent(
                "ab_exposures", "ignored-source-key", "{\"eventId\":\"e-2\"}", java.util.Map.of());

        try {
            assertThat(publisher.publish(event)).isTrue();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(producer.history()).singleElement()
                            .satisfies(record -> {
                                assertThat(record.key()).isNull();
                                assertThat(record.value()).isEqualTo(event.value());
                            }));
        } finally {
            publisher.close();
        }
    }
}
