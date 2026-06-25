package com.recsys.config;

import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import com.recsys.infrastructure.messaging.KafkaAsyncEventPublisher;
import com.recsys.infrastructure.messaging.SqsAsyncEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class ModelEventConfig {

    /**
     * Bounded, fire-and-forget publisher for A/B exposure events. Ships to Kafka when configured
     * ({@code recsys.events.kafka.enabled} + a non-blank bootstrap), or SQS when configured
     * ({@code recsys.events.sqs.enabled} + a non-blank queue URL), otherwise the log-only base
     * (local dev / tests / demo need no broker). Closed on context shutdown.
     */
    @Bean(destroyMethod = "close")
    public AsyncEventPublisher abExposurePublisher(
            @Value("${recsys.events.sqs.enabled:false}") boolean sqsEnabled,
            @Value("${recsys.events.sqs.queue-url:}") String sqsQueueUrl,
            @Value("${recsys.events.sqs.region:${AWS_REGION:us-east-1}}") String sqsRegion,
            @Value("${recsys.events.kafka.enabled:false}") boolean kafkaEnabled,
            @Value("${recsys.events.kafka.bootstrap-servers:}") String bootstrapServers,
            @Value("${recsys.events.kafka.exposure-topic:ab_exposures}") String topic) {
        if (sqsEnabled && !sqsQueueUrl.isBlank()) {
            SqsClient sqsClient = SqsClient.builder()
                    .region(Region.of(sqsRegion == null || sqsRegion.isBlank() ? "us-east-1" : sqsRegion.trim()))
                    .build();
            return new SqsAsyncEventPublisher(sqsClient, sqsQueueUrl, true);
        }
        if (kafkaEnabled && !bootstrapServers.isBlank()) {
            return new KafkaAsyncEventPublisher(bootstrapServers, topic);
        }
        return new AsyncEventPublisher();
    }
}
