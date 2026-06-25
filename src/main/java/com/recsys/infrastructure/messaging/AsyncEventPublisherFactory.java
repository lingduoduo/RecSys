package com.recsys.infrastructure.messaging;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class AsyncEventPublisherFactory {

    private AsyncEventPublisherFactory() {}

    public static AsyncEventPublisher fromEnvironment(String envPrefix) {
        return from(envPrefix, System.getenv(), region -> SqsClient.builder().region(region).build());
    }

    static AsyncEventPublisher from(String envPrefix,
                                    Map<String, String> env,
                                    Function<Region, SqsClient> sqsClientFactory) {
        String prefix = requirePrefix(envPrefix);
        Map<String, String> source = env == null ? Map.of() : env;
        Function<Region, SqsClient> clientFactory =
                Objects.requireNonNull(sqsClientFactory, "sqsClientFactory");

        boolean sqsEnabled = readBoolean(source, prefix + "_SQS_ENABLED");
        String sqsQueueUrl = source.getOrDefault(prefix + "_SQS_QUEUE_URL", "");
        if (sqsEnabled && !sqsQueueUrl.isBlank()) {
            Region region = Region.of(readRegion(source));
            return new SqsAsyncEventPublisher(clientFactory.apply(region), sqsQueueUrl, true);
        }

        boolean kafkaEnabled = readBoolean(source, prefix + "_KAFKA_ENABLED");
        String bootstrapServers = source.getOrDefault(prefix + "_KAFKA_BOOTSTRAP_SERVERS", "");
        if (kafkaEnabled && !bootstrapServers.isBlank()) {
            String topic = source.getOrDefault(prefix + "_KAFKA_TOPIC", "online_events");
            return new KafkaAsyncEventPublisher(bootstrapServers, topic);
        }

        return new AsyncEventPublisher();
    }

    private static boolean readBoolean(Map<String, String> env, String key) {
        return Boolean.parseBoolean(env.getOrDefault(key, "false"));
    }

    private static String readRegion(Map<String, String> env) {
        String region = env.getOrDefault("AWS_REGION", "us-east-1");
        return region == null || region.isBlank() ? "us-east-1" : region.trim();
    }

    private static String requirePrefix(String envPrefix) {
        if (envPrefix == null || envPrefix.isBlank()) {
            throw new IllegalArgumentException("envPrefix must not be blank");
        }
        return envPrefix.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
