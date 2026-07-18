package com.recsys.online.flink;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaTopicPartitionValidatorTest {

    @Test
    void acceptsExpectedPartitionCount() {
        Admin admin = adminReturning("movie-events-v2", 24);

        assertThatCode(() -> KafkaTopicPartitionValidator.validate(admin, "movie-events-v2", 24))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingTopic() throws Exception {
        Admin admin = mock(Admin.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<TopicDescription> failed = mock(KafkaFuture.class);
        when(failed.get(10L, TimeUnit.SECONDS)).thenThrow(
                new ExecutionException(new UnknownTopicOrPartitionException("missing")));
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.topicNameValues()).thenReturn(Map.of("missing", failed));
        when(admin.describeTopics(List.of("missing"))).thenReturn(result);

        assertThatThrownBy(() -> KafkaTopicPartitionValidator.validate(admin, "missing", 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsTooFewPartitions() {
        assertThatThrownBy(() -> KafkaTopicPartitionValidator.validate(
                adminReturning("movie-events-v2", 23), "movie-events-v2", 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("23")
                .hasMessageContaining("24");
    }

    @Test
    void rejectsTooManyPartitions() {
        assertThatThrownBy(() -> KafkaTopicPartitionValidator.validate(
                adminReturning("movie-events-v2", 25), "movie-events-v2", 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("25")
                .hasMessageContaining("24");
    }

    private static Admin adminReturning(String topic, int partitionCount) {
        Node leader = new Node(1, "localhost", 9092);
        List<TopicPartitionInfo> partitions = new ArrayList<>();
        for (int partition = 0; partition < partitionCount; partition++) {
            partitions.add(new TopicPartitionInfo(partition, leader, List.of(leader), List.of(leader)));
        }
        TopicDescription description = new TopicDescription(topic, false, partitions);
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.topicNameValues()).thenReturn(
                Map.of(topic, KafkaFuture.completedFuture(description)));
        Admin admin = mock(Admin.class);
        when(admin.describeTopics(List.of(topic))).thenReturn(result);
        return admin;
    }
}
