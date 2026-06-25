package com.recsys.application.saga;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SagaEventPublishersTest {

    @Test
    void fromReturnsNoopWhenDisabled() {
        SagaEventPublisher publisher = SagaEventPublishers.from(
                Map.of(), region -> mock(SqsClient.class));

        assertThat(publisher).isSameAs(SagaEventPublisher.NOOP);
    }

    @Test
    void fromReturnsNoopWhenEnabledWithBlankQueueUrl() {
        SagaEventPublisher publisher = SagaEventPublishers.from(
                Map.of(
                        "SAGA_EVENTS_SQS_ENABLED", "true",
                        "SAGA_EVENTS_SQS_QUEUE_URL", "   "),
                region -> mock(SqsClient.class));

        assertThat(publisher).isSameAs(SagaEventPublisher.NOOP);
    }

    @Test
    void fromReturnsSqsPublisherWhenEnabledWithQueueUrl() {
        AtomicReference<Region> regionSeen = new AtomicReference<>();
        SagaEventPublisher publisher = SagaEventPublishers.from(
                Map.of(
                        "SAGA_EVENTS_SQS_ENABLED", "true",
                        "SAGA_EVENTS_SQS_QUEUE_URL", "queue-url",
                        "SAGA_EVENTS_SQS_BEST_EFFORT", "true",
                        "AWS_REGION", "us-west-2"),
                region -> {
                    regionSeen.set(region);
                    return mock(SqsClient.class);
                });

        assertThat(publisher).isInstanceOf(SqsSagaEventPublisher.class);
        assertThat(regionSeen.get()).isEqualTo(Region.US_WEST_2);
    }
}
