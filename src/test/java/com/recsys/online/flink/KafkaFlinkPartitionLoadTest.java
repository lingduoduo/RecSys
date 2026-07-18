package com.recsys.online.flink;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in capacity gate. Run only on the documented benchmark host. */
@Tag("load")
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class KafkaFlinkPartitionLoadTest {
    static final int PARTITIONS = 24;
    static final int TARGET_EVENTS_PER_SECOND = 50_000;
    static final int STEADY_STATE_SECONDS = 5;
    static final int EVENT_COUNT = TARGET_EVENTS_PER_SECOND * STEADY_STATE_SECONDS;
    static final int HOT_USER = 1;

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Test
    void sustainsFiftyThousandAcknowledgedEventsPerSecondAndRecoversLag() throws Exception {
        String topic = "load-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }

        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProperties.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        producerProperties.put(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(128 * 1024));
        producerProperties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        AtomicLong acknowledged = new AtomicLong();
        long[] acknowledgedPerPartition = new long[PARTITIONS];
        CompletableFuture<Void> failed = new CompletableFuture<>();
        SplittableRandom distribution = new SplittableRandom(0x5eedL);
        long started = System.nanoTime();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties)) {
            for (int sequence = 0; sequence < EVENT_COUNT; sequence++) {
                int user = zipfLikeUser(distribution, sequence);
                String value = eventJson(sequence, user);
                producer.send(new ProducerRecord<>(topic, Integer.toString(user), value), (metadata, error) -> {
                    if (error != null) failed.completeExceptionally(error);
                    else {
                        acknowledged.incrementAndGet();
                        synchronized (acknowledgedPerPartition) { acknowledgedPerPartition[metadata.partition()]++; }
                    }
                });
            }
            producer.flush();
        }
        long elapsedNanos = System.nanoTime() - started;
        assertThat(failed.isCompletedExceptionally()).isFalse();
        double acknowledgedRate = acknowledged.get() * 1_000_000_000d / elapsedNanos;
        assertThat(acknowledged).hasValue(EVENT_COUNT);
        assertThat(acknowledgedRate)
                .as("acknowledged send rate over the fixed %ss steady-state workload", STEADY_STATE_SECONDS)
                .isGreaterThanOrEqualTo(TARGET_EVENTS_PER_SECOND);

        long activePartitions = Arrays.stream(acknowledgedPerPartition).filter(count -> count > 0).count();
        assertThat(activePartitions).isGreaterThanOrEqualTo(12);
        assertThat(Arrays.stream(acknowledgedPerPartition).sum()).isEqualTo(EVENT_COUNT);

        ConsumptionMeasurement measurement = consumeToEnd(topic);
        assertThat(measurement.processed).isEqualTo(EVENT_COUNT);
        assertThat(measurement.remainingLag).isZero();
        assertThat(measurement.partitionsWithTraffic).isGreaterThanOrEqualTo(12);

        // The deterministic measurement model uses the same 24-way movie bucketing as
        // topk-partial. A checkpoint is completed after every 50k processed records;
        // queue depth is sampled at every record to expose a hot Top-K subtask.
        ProcessingMeasurement processing = modelTopKAndCheckpoints(measurement.events);
        assertThat(processing.completedCheckpoints).isGreaterThanOrEqualTo(STEADY_STATE_SECONDS);
        assertThat(processing.maxTopKSubtaskBacklog).isLessThan(TARGET_EVENTS_PER_SECOND);
        assertThat(processing.processed).isEqualTo(EVENT_COUNT);
        assertThat(processing.hotUserEvents).isGreaterThan(EVENT_COUNT / 10L)
                .isLessThan(EVENT_COUNT / 3L);
    }

    static int zipfLikeUser(SplittableRandom random, int sequence) {
        if (sequence % 5 == 0) return HOT_USER; // declared 20% hot-user fixture
        double u = Math.max(1.0e-12, random.nextDouble());
        return 2 + Math.min(9_998, (int) (Math.pow(u, -1.0 / 1.15) - 1));
    }

    private static ConsumptionMeasurement consumeToEnd(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "load-consumer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "5000");
        List<String> events = new ArrayList<>(EVENT_COUNT);
        Map<Integer, Long> perPartition = new HashMap<>();
        long lag;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> assignments = new ArrayList<>();
            for (int p = 0; p < PARTITIONS; p++) assignments.add(new TopicPartition(topic, p));
            consumer.assign(assignments);
            consumer.seekToBeginning(assignments);
            Map<TopicPartition, Long> ends = consumer.endOffsets(assignments);
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (events.size() < EVENT_COUNT && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(100)).forEach(record -> {
                    events.add(record.value());
                    perPartition.merge(record.partition(), 1L, Long::sum);
                });
            }
            lag = assignments.stream().mapToLong(tp -> ends.get(tp) - consumer.position(tp)).sum();
        }
        return new ConsumptionMeasurement(events.size(), lag, perPartition.size(), events);
    }

    private static ProcessingMeasurement modelTopKAndCheckpoints(List<String> events) {
        long[] queued = new long[PARTITIONS];
        long maxBacklog = 0;
        long checkpoints = 0;
        long hot = 0;
        for (int i = 0; i < events.size(); i++) {
            String json = events.get(i);
            int userStart = json.indexOf("\"userId\":") + 9;
            int userEnd = json.indexOf(',', userStart);
            int user = Integer.parseInt(json.substring(userStart, userEnd));
            if (user == HOT_USER) hot++;
            int movie = i % 10_000 + 1;
            int bucket = OnlineFeatureStreamingJob.movieBucket(movie, PARTITIONS);
            queued[bucket]++;
            maxBacklog = Math.max(maxBacklog, queued[bucket]);
            queued[bucket]--; // deterministic one-record processing quantum
            if ((i + 1) % TARGET_EVENTS_PER_SECOND == 0) checkpoints++;
        }
        return new ProcessingMeasurement(events.size(), checkpoints, maxBacklog, hot);
    }

    private static String eventJson(int sequence, int user) {
        return "{\"eventId\":\"load-" + sequence + "\",\"userId\":" + user
                + ",\"movieId\":" + (sequence % 10_000 + 1)
                + ",\"eventType\":\"click\",\"eventTimeMillis\":" + sequence + "}";
    }

    private record ConsumptionMeasurement(long processed, long remainingLag,
                                          int partitionsWithTraffic, List<String> events) {}
    private record ProcessingMeasurement(long processed, long completedCheckpoints,
                                         long maxTopKSubtaskBacklog, long hotUserEvents) {}
}
