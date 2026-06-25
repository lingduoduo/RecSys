package com.recsys.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AsyncEventPublisherFactoryTest {

    @Test
    void fromDefaultsToBasePublisherWhenNoTransportConfigured() {
        AsyncEventPublisher publisher = AsyncEventPublisherFactory.from(
                "ONLINE_EVENTS", Map.of(), region -> mock(SqsClient.class));

        assertThat(publisher).isExactlyInstanceOf(AsyncEventPublisher.class);
        publisher.close();
    }

    @Test
    void fromBuildsSqsPublisherWhenQueueConfigured() {
        AtomicReference<Region> regionSeen = new AtomicReference<>();
        AsyncEventPublisher publisher = AsyncEventPublisherFactory.from(
                "ONLINE_EVENTS",
                Map.of(
                        "ONLINE_EVENTS_SQS_ENABLED", "true",
                        "ONLINE_EVENTS_SQS_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/123/online",
                        "AWS_REGION", "us-west-2"),
                region -> {
                    regionSeen.set(region);
                    return mock(SqsClient.class);
                });

        assertThat(publisher).isInstanceOf(SqsAsyncEventPublisher.class);
        assertThat(regionSeen.get()).isEqualTo(Region.US_WEST_2);
        publisher.close();
    }

    @Test
    void fromFallsThroughWhenSqsQueueUrlBlank() {
        AsyncEventPublisher publisher = AsyncEventPublisherFactory.from(
                "ONLINE_EVENTS",
                Map.of(
                        "ONLINE_EVENTS_SQS_ENABLED", "true",
                        "ONLINE_EVENTS_SQS_QUEUE_URL", "   "),
                region -> mock(SqsClient.class));

        assertThat(publisher).isExactlyInstanceOf(AsyncEventPublisher.class);
        publisher.close();
    }

    @Test
    void fromBuildsKafkaPublisherWhenKafkaConfiguredAndSqsDisabled() {
        AsyncEventPublisher publisher = AsyncEventPublisherFactory.from(
                "ONLINE_EVENTS",
                Map.of(
                        "ONLINE_EVENTS_KAFKA_ENABLED", "true",
                        "ONLINE_EVENTS_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092",
                        "ONLINE_EVENTS_KAFKA_TOPIC", "online_events"),
                region -> mock(SqsClient.class));

        assertThat(publisher).isInstanceOf(KafkaAsyncEventPublisher.class);
        publisher.close();
    }
}
