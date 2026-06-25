package com.recsys.application.saga;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class SagaEventPublishers {

    private SagaEventPublishers() {}

    public static SagaEventPublisher fromEnvironment() {
        return from(System.getenv(), region -> SqsClient.builder().region(region).build());
    }

    static SagaEventPublisher from(Map<String, String> env,
                                   Function<Region, SqsClient> sqsClientFactory) {
        Map<String, String> source = env == null ? Map.of() : env;
        Function<Region, SqsClient> clientFactory =
                Objects.requireNonNull(sqsClientFactory, "sqsClientFactory");

        boolean enabled = Boolean.parseBoolean(source.getOrDefault("SAGA_EVENTS_SQS_ENABLED", "false"));
        String queueUrl = source.getOrDefault("SAGA_EVENTS_SQS_QUEUE_URL", "");
        if (!enabled || queueUrl.isBlank()) {
            return SagaEventPublisher.NOOP;
        }

        String regionName = source.getOrDefault("AWS_REGION", "us-east-1");
        Region region = Region.of(regionName == null || regionName.isBlank() ? "us-east-1" : regionName.trim());
        boolean bestEffort = Boolean.parseBoolean(source.getOrDefault("SAGA_EVENTS_SQS_BEST_EFFORT", "false"));
        return new SqsSagaEventPublisher(clientFactory.apply(region), queueUrl, new com.fasterxml.jackson.databind.ObjectMapper(), bestEffort);
    }
}
