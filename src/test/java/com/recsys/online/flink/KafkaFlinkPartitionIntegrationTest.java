package com.recsys.online.flink;

import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.Collector;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class KafkaFlinkPartitionIntegrationTest {
    private static final int PARTITIONS = 24;

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Test
    void keyedTrafficUsesManyPartitionsAndPreservesPerUserOrderThroughDedup() throws Exception {
        String topic = topic("ordered");
        createTopic(topic, PARTITIONS);
        List<MovieEvent> expected = new ArrayList<>();
        try (KafkaProducer<String, String> producer = producer()) {
            for (int sequence = 0; sequence < 40; sequence++) {
                for (int user = 1; user <= 96; user++) {
                    String eventId = (sequence % 2 == 0 ? "Aa" : "BB") + '-' + user + '-' + sequence;
                    MovieEvent event = event(eventId, user, sequence + 1, sequence);
                    expected.add(event);
                    producer.send(new ProducerRecord<>(topic, Integer.toString(user), json(event))).get(10, TimeUnit.SECONDS);
                }
            }
        }

        List<Consumed> consumed = consume(topic, expected.size());
        assertThat(consumed.stream().map(Consumed::partition).collect(java.util.stream.Collectors.toSet()))
                .as("representative traffic must exercise at least half of the 24 partitions")
                .hasSizeGreaterThanOrEqualTo(12);
        Map<Integer, List<MovieEvent>> byUser = new HashMap<>();
        for (Consumed record : consumed) byUser.computeIfAbsent(record.event.userId, ignored -> new ArrayList<>()).add(record.event);
        assertThat(byUser).hasSize(96);
        byUser.forEach((user, events) -> assertThat(events).extracting(e -> e.eventTimeMillis)
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, 40).boxed().toList()));

        // "Aa" and "BB" deliberately collide in String.hashCode; appendages make event IDs
        // vary independently of the Kafka key. Dedup must still be keyed by user, not event ID.
        assertThat(expected.stream().map(e -> e.eventId.hashCode()).distinct().count()).isGreaterThan(40);
        List<MovieEvent> deduped = runDedup(consumed.stream().map(Consumed::event).toList());
        assertThat(deduped).hasSameSizeAs(expected);
        deduped.stream().collect(java.util.stream.Collectors.groupingBy(e -> e.userId))
                .forEach((user, events) -> assertThat(events).extracting(e -> e.eventTimeMillis)
                        .containsExactlyElementsOf(java.util.stream.LongStream.range(0, 40).boxed().toList()));
    }

    @Test
    void replayIsDeduplicatedWithinUserButSameEventIdForAnotherUserSurvives() throws Exception {
        List<MovieEvent> input = List.of(event("replay", 7, 1, 1), event("replay", 7, 2, 2), event("replay", 8, 3, 3));
        assertThat(runDedup(input)).extracting(e -> e.userId, e -> e.movieId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(7, 1), org.assertj.core.groups.Tuple.tuple(8, 3));
    }

    @Test
    void realKafkaTopicMismatchFailsValidation() throws Exception {
        String topic = topic("mismatch");
        createTopic(topic, PARTITIONS - 1);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            assertThatThrownBy(() -> KafkaTopicPartitionValidator.validate(admin, topic, PARTITIONS))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("23").hasMessageContaining("24");
        }
    }

    @Test
    void twoStageTopKExactlyMatchesOracleAcrossTwentyFourBuckets() throws Exception {
        List<MovieEvent> events = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) events.add(event("top-" + i, i % 997 + 1, i % 211 + 1, i));
        int k = 30;
        List<OnlineFeatureStreamingJob.PartialTopK> partials = new ArrayList<>();
        var partialFunction = new OnlineFeatureStreamingJob.PartialTopKWindowFunction(k);
        for (int bucket = 0; bucket < PARTITIONS; bucket++) {
            List<MovieEvent> values = new ArrayList<>();
            for (MovieEvent event : events) if (OnlineFeatureStreamingJob.movieBucket(event.movieId, PARTITIONS) == bucket) values.add(event);
            partialFunction.apply(bucket, new TimeWindow(0, 1_000), values, collector(partials));
        }
        List<OnlineFeatureStreamingJob.ScoredMovie> actual =
                OnlineFeatureStreamingJob.FinalTopKWindowFunction.mergeTopK(partials, k);
        Map<Integer, Long> scores = new HashMap<>();
        events.forEach(e -> scores.merge(e.movieId, e.engagementWeight(), Long::sum));
        List<Map.Entry<Integer, Long>> oracle = scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
                .limit(k).toList();
        assertThat(actual).extracting(m -> m.movieId).containsExactlyElementsOf(oracle.stream().map(Map.Entry::getKey).toList());
        assertThat(actual).extracting(m -> m.score).containsExactlyElementsOf(oracle.stream().map(Map.Entry::getValue).toList());
    }

    @Test
    void dedupStateRestoresAtChangedParallelismWithStableMaxParallelism() throws Exception {
        int maxParallelism = 128;
        OperatorSubtaskState snapshot;
        var first = dedupHarness(maxParallelism, 1, 0);
        try (first) {
            first.open();
            first.processElement(new StreamRecord<>(event("saved", 41, 1, 1)));
            snapshot = first.snapshot(1, 1);
        }
        // Restore the state into the subtask that owns user 41 after scaling 1 -> 2.
        int owner = org.apache.flink.runtime.state.KeyGroupRangeAssignment.assignKeyToParallelOperator(41, maxParallelism, 2);
        OperatorSubtaskState repartitioned = org.apache.flink.streaming.util.AbstractStreamOperatorTestHarness
                .repartitionOperatorState(snapshot, maxParallelism, 1, 2, owner);
        var restored = dedupHarness(maxParallelism, 2, owner);
        try (restored) {
            restored.initializeState(repartitioned);
            restored.open();
            restored.processElement(new StreamRecord<>(event("saved", 41, 2, 2)));
            assertThat(records(restored)).isEmpty();
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<Integer, MovieEvent, MovieEvent> dedupHarness(
            int maxParallelism, int parallelism, int subtask) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(
                new OnlineFeatureStreamingJob.DeduplicateEventsFunction(3_600)), e -> e.userId,
                Types.INT, maxParallelism, parallelism, subtask);
    }

    private static List<MovieEvent> runDedup(List<MovieEvent> input) throws Exception {
        try (var harness = dedupHarness(128, 1, 0)) {
            harness.open();
            for (MovieEvent event : input) harness.processElement(new StreamRecord<>(event));
            return records(harness);
        }
    }

    private static List<MovieEvent> records(KeyedOneInputStreamOperatorTestHarness<Integer, MovieEvent, MovieEvent> harness) {
        List<MovieEvent> output = new ArrayList<>();
        for (Object item : harness.getOutput()) if (item instanceof StreamRecord<?> record) output.add((MovieEvent) record.getValue());
        return output;
    }

    private static KafkaProducer<String, String> producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(p);
    }

    private static List<Consumed> consume(String topic, int expected) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "integration-" + UUID.randomUUID());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<Consumed> result = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (result.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(r -> result.add(new Consumed(r.partition(), parse(r.value()))));
            }
        }
        assertThat(result).hasSize(expected);
        return result;
    }

    private static void createTopic(String topic, int partitions) throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
    }

    private static String topic(String prefix) { return prefix + '-' + UUID.randomUUID(); }
    private static MovieEvent event(String id, int user, int movie, long sequence) {
        MovieEvent event = new MovieEvent(); event.eventId = id; event.userId = user; event.movieId = movie;
        event.eventType = "click"; event.eventTimeMillis = sequence; return event;
    }
    private static String json(MovieEvent e) { return "{\"eventId\":\"" + e.eventId + "\",\"userId\":" + e.userId + ",\"movieId\":" + e.movieId + ",\"eventType\":\"click\",\"eventTimeMillis\":" + e.eventTimeMillis + "}"; }
    private static MovieEvent parse(String json) { try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, MovieEvent.class); } catch (Exception e) { throw new AssertionError(e); } }
    private static <T> Collector<T> collector(List<T> out) { return new Collector<>() { public void collect(T value) { out.add(value); } public void close() {} }; }
    private record Consumed(int partition, MovieEvent event) {}
}
