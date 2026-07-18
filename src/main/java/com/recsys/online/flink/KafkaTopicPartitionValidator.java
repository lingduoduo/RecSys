package com.recsys.online.flink;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class KafkaTopicPartitionValidator {
    private static final long TIMEOUT_SECONDS = 10L;

    private KafkaTopicPartitionValidator() {}

    public static void validate(Admin admin, String topic, int expectedPartitions) {
        try {
            TopicDescription description = admin.describeTopics(List.of(topic))
                    .topicNameValues()
                    .get(topic)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int actualPartitions = description.partitions().size();
            if (actualPartitions != expectedPartitions) {
                throw new IllegalStateException("Kafka topic '" + topic + "' has " + actualPartitions
                        + " partitions; expected " + expectedPartitions);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to validate Kafka topic '" + topic + "'", e);
        }
    }
}
