package com.recsys.config;

import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import com.recsys.infrastructure.messaging.KafkaAsyncEventPublisher;
import com.recsys.infrastructure.messaging.SqsAsyncEventPublisher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelEventConfigTest {

    private final ModelEventConfig config = new ModelEventConfig();

    @Test
    void sqsEnabledWithQueueUrl_buildsSqsPublisher() {
        AsyncEventPublisher pub = config.abExposurePublisher(
                true, "https://sqs.us-east-1.amazonaws.com/123/ab-exposures", "us-east-1",
                true, "localhost:9092", "ab_exposures");
        assertThat(pub).isInstanceOf(SqsAsyncEventPublisher.class);
        pub.close();
    }

    @Test
    void enabledWithBootstrap_buildsKafkaPublisher() {
        AsyncEventPublisher pub = config.abExposurePublisher(
                false, "", "us-east-1",
                true, "localhost:9092", "ab_exposures");
        assertThat(pub).isInstanceOf(KafkaAsyncEventPublisher.class);
        pub.close();
    }

    @Test
    void disabled_buildsBaseLogOnlyPublisher() {
        AsyncEventPublisher pub = config.abExposurePublisher(
                false, "", "us-east-1",
                false, "localhost:9092", "ab_exposures");
        assertThat(pub).isExactlyInstanceOf(AsyncEventPublisher.class);   // base, not the Kafka subclass
        pub.close();
    }

    @Test
    void enabledButBlankDestinations_buildsBaseLogOnlyPublisher() {
        AsyncEventPublisher pub = config.abExposurePublisher(
                true, "   ", "us-east-1",
                true, "   ", "ab_exposures");
        assertThat(pub).isExactlyInstanceOf(AsyncEventPublisher.class);
        pub.close();
    }
}
