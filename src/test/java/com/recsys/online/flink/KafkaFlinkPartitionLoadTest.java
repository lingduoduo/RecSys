package com.recsys.online.flink;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.checkpoint.CheckpointStatsSnapshot;
import org.apache.flink.runtime.executiongraph.AccessExecutionGraph;
import org.apache.flink.runtime.executiongraph.AccessExecutionJobVertex;
import org.apache.flink.runtime.executiongraph.AccessExecutionVertex;
import org.apache.flink.runtime.executiongraph.IOMetrics;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in capacity gate. Its figures are valid only for the host on which it runs. */
@Tag("load")
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class KafkaFlinkPartitionLoadTest {
    static final int PARTITIONS = 24;
    static final int TARGET_EVENTS_PER_SECOND = 50_000;
    static final int EVENTS_PER_INTERVAL = 55_000;
    static final int STEADY_STATE_SECONDS = 5;
    static final int HOT_USER = 1;
    private static final String GROUP = "partition-load";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private MiniCluster cluster;
    private JobID jobId;

    @AfterEach
    void stopCluster() throws Exception {
        if (cluster != null && jobId != null) {
            try { cluster.cancelJob(jobId).get(30, TimeUnit.SECONDS); } catch (Exception ignored) { }
        }
        if (cluster != null) cluster.closeAsync().get(30, TimeUnit.SECONDS);
        LoadSink.clear();
    }

    @Test
    void sustainsTargetThroughTheProductionGraphAndRecoversLag() throws Exception {
        String topic = "load-" + UUID.randomUUID();
        createTopic(topic);
        startGraph(topic);

        long[] perPartition = new long[PARTITIONS];
        long[] hotPerPartition = new long[PARTITIONS];
        List<Long> acknowledgedRates = new ArrayList<>();
        List<Long> observedLag = new ArrayList<>();
        List<Long> processedAtIntervalEnd = new ArrayList<>();
        AtomicLong acknowledged = new AtomicLong();
        CompletableFuture<Void> sendFailure = new CompletableFuture<>();
        long baseEventTime = System.currentTimeMillis();

        try (Admin admin = admin(); KafkaProducer<String, String> producer = producer()) {
            for (int second = 0; second < STEADY_STATE_SECONDS; second++) {
                long before = acknowledged.get();
                long intervalStart = System.nanoTime();
                int firstSequence = second * EVENTS_PER_INTERVAL;
                for (int offset = 0; offset < EVENTS_PER_INTERVAL; offset++) {
                    int sequence = firstSequence + offset;
                    int user = sequence % 5 == 0 ? HOT_USER : 2 + sequence % 99_999;
                    producer.send(new ProducerRecord<>(topic, Integer.toString(user),
                            eventJson(sequence, user, baseEventTime + sequence)), (metadata, error) -> {
                        if (error != null) sendFailure.completeExceptionally(error);
                        else {
                            acknowledged.incrementAndGet();
                            synchronized (perPartition) {
                                perPartition[metadata.partition()]++;
                                if (user == HOT_USER) hotPerPartition[metadata.partition()]++;
                            }
                        }
                    });
                    if (offset > 0 && offset % 10_000 == 0) observedLag.add(consumerLag(admin, topic));
                }
                producer.flush();
                long remainingNanos = TimeUnit.SECONDS.toNanos(1L)
                        - (System.nanoTime() - intervalStart);
                if (remainingNanos > 0L) TimeUnit.NANOSECONDS.sleep(remainingNanos);
                long elapsed = System.nanoTime() - intervalStart;
                long intervalAcks = acknowledged.get() - before;
                acknowledgedRates.add((long) (intervalAcks * 1_000_000_000d / elapsed));
                observedLag.add(consumerLag(admin, topic));
                processedAtIntervalEnd.add(LoadSink.processed());
            }

            // Advance every source partition's watermark so the real two-stage Top-K emits.
            for (int user = 200_000; user < 201_000; user++) {
                int sequence = STEADY_STATE_SECONDS * EVENTS_PER_INTERVAL + user;
                producer.send(new ProducerRecord<>(topic, Integer.toString(user),
                        eventJson(sequence, user, baseEventTime + 10_000L)), (metadata, error) -> {
                    if (error != null) sendFailure.completeExceptionally(error);
                    else {
                        acknowledged.incrementAndGet();
                        synchronized (perPartition) { perPartition[metadata.partition()]++; }
                    }
                });
            }
            producer.flush();

            assertThat(sendFailure.isCompletedExceptionally()).isFalse();
            assertThat(acknowledgedRates).as("acknowledged rate for every one-second workload interval")
                    .allMatch(rate -> rate >= TARGET_EVENTS_PER_SECOND);
            await(Duration.ofSeconds(90), () -> LoadSink.processed() == acknowledged.get());
            await(Duration.ofSeconds(30), () -> {
                try { return consumerLag(admin, topic) == 0L; } catch (Exception e) { return false; }
            });
            observedLag.add(consumerLag(admin, topic));
        }

        assertThat(LoadSink.processed()).as("records emitted by production dedup stage")
                .isEqualTo(acknowledged.get());
        assertThat(LoadSink.orderViolations()).as("same-user order violations").isZero();
        assertThat(LoadSink.snapshots()).as("real final Top-K output").isPositive();
        assertThat(observedLag).as("Kafka consumer lag sampled during production and after recovery")
                .anyMatch(lag -> lag > 0L).endsWith(0L);
        assertThat(processedAtIntervalEnd.get(0)).as("the running graph must make progress during warmup")
                .isPositive();
        for (int interval = 1; interval < processedAtIntervalEnd.size(); interval++) {
            assertThat(processedAtIntervalEnd.get(interval))
                    .as("sink progress by the end of steady-state interval %s", interval + 1)
                    .isGreaterThan(processedAtIntervalEnd.get(interval - 1));
        }
        // Two bounded intervals are warmup; thereafter the end-to-end sink, not merely Kafka acks,
        // must sustain the advertised capacity.
        for (int interval = 2; interval < processedAtIntervalEnd.size(); interval++) {
            long processedDelta = processedAtIntervalEnd.get(interval)
                    - processedAtIntervalEnd.get(interval - 1);
            assertThat(processedDelta)
                    .as("end-to-end processed rate after warmup, interval %s", interval + 1)
                    .isGreaterThanOrEqualTo(TARGET_EVENTS_PER_SECOND);
        }
        assertThat(observedLag.stream().mapToLong(Long::longValue).max().orElseThrow())
                .as("lag remains bounded during load and returns to zero during recovery")
                .isLessThanOrEqualTo(3L * EVENTS_PER_INTERVAL);
        assertPartitionPolicy(perPartition, hotPerPartition);

        AccessExecutionGraph graph = cluster.getExecutionGraph(jobId).get(30, TimeUnit.SECONDS);
        CheckpointStatsSnapshot checkpoints = graph.getCheckpointStatsSnapshot();
        assertThat(checkpoints).isNotNull();
        assertThat(checkpoints.getCounts().getNumberOfCompletedCheckpoints())
                .as("completed checkpoints reported by the live execution graph").isPositive();
        assertThat(checkpoints.getCounts().getNumberOfFailedCheckpoints())
                .as("failed checkpoints reported by the live execution graph").isZero();
        var latestCheckpoint = checkpoints.getHistory().getLatestCompletedCheckpoint();
        assertThat(latestCheckpoint).isNotNull();
        assertThat(latestCheckpoint.getEndToEndDuration()).as("checkpoint duration evidence").isPositive();
        assertThat(latestCheckpoint.getLatestAckTimestamp()).as("checkpoint completion timestamp evidence")
                .isPositive();
        assertThat(System.currentTimeMillis() - latestCheckpoint.getLatestAckTimestamp())
                .as("latest completed checkpoint age").isLessThan(30_000L);

        AccessExecutionJobVertex finalTopK = graph.getAllVertices().values().stream()
                .filter(vertex -> vertex.getName().contains("topk-final"))
                .findFirst().orElseThrow();
        List<String> metricValues = new ArrayList<>();
        int activeSubtasks = 0;
        for (AccessExecutionVertex subtask : finalTopK.getTaskVertices()) {
            IOMetrics metrics = subtask.getCurrentExecutionAttempt().getIOMetrics();
            assertThat(metrics).as("live I/O metrics for %s", subtask.getTaskNameWithSubtaskIndex()).isNotNull();
            double busy = metrics.getAccumulateBusyTime();
            long backpressured = metrics.getAccumulateBackPressuredTime();
            long idle = metrics.getAccumulateIdleTime();
            assertThat(busy).isGreaterThanOrEqualTo(0d);
            assertThat(backpressured).isGreaterThanOrEqualTo(0L);
            assertThat(idle).isGreaterThanOrEqualTo(0L);
            double activeMillis = busy + backpressured;
            double ratio = activeMillis == 0d ? 0d : backpressured / activeMillis;
            metricValues.add(subtask.getParallelSubtaskIndex() + ":busy=" + busy
                    + ",backpressured=" + backpressured + ",idle=" + idle + ",ratio=" + ratio);
            if (busy > 0d) activeSubtasks++;
            assertThat(ratio)
                    .as("final Top-K continuously-backpressured threshold is <0.95; metrics=%s", metricValues)
                    .isLessThan(0.95d);
        }
        assertThat(activeSubtasks).as("at least one final Top-K subtask must report busy time; metrics=%s",
                metricValues).isPositive();
    }

    private void startGraph(String topic) throws Exception {
        Configuration miniConfiguration = new Configuration();
        cluster = new MiniCluster(new MiniClusterConfiguration.Builder()
                .setConfiguration(miniConfiguration).setNumTaskManagers(1)
                .setNumSlotsPerTaskManager(PARTITIONS).build());
        cluster.start();

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(PARTITIONS);
        env.setRestartStrategy(RestartStrategies.noRestart());
        env.enableCheckpointing(500L);
        ParameterTool params = ParameterTool.fromMap(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "topic", topic,
                "bridge-mode", "true", "checkpoint-dir", "file:///tmp/recsys-flink-load-checkpoints",
                "group.id", GROUP, "expected-topic-partitions", "24",
                "source-parallelism", "24", "operator-parallelism", "24",
                "max-parallelism", "128", "kafka.partition.discovery.interval.ms", "100"));
        OnlineFeatureStreamingJob.JobConfiguration jobConfiguration =
                OnlineFeatureStreamingJob.validateConfiguration(params);
        OnlineFeatureStreamingJob.PartitionGraph production = OnlineFeatureStreamingJob.buildPartitionGraph(
                OnlineFeatureStreamingJob.buildEventStream(env, params, jobConfiguration), jobConfiguration,
                10, PARTITIONS, 1_000L, 100L, 200L);
        production.events().addSink(new LoadSink<>(false)).name("load-events");
        production.snapshots().addSink(new LoadSink<>(true)).name("load-snapshots");
        JobGraph jobGraph = env.getStreamGraph().getJobGraph();
        jobId = jobGraph.getJobID();
        cluster.submitJob(jobGraph).get(30, TimeUnit.SECONDS);
        await(Duration.ofSeconds(30), () -> {
            try { return cluster.getJobStatus(jobId).get(5, TimeUnit.SECONDS) == JobStatus.RUNNING; }
            catch (Exception e) { return false; }
        });
    }

    private static void assertPartitionPolicy(long[] counts, long[] hotCounts) {
        long[] ordinary = new long[PARTITIONS];
        for (int i = 0; i < PARTITIONS; i++) ordinary[i] = counts[i] - hotCounts[i];
        long[] active = Arrays.stream(ordinary).filter(value -> value > 0).sorted().toArray();
        assertThat(active).hasSizeGreaterThanOrEqualTo(12);
        long median = active[active.length / 2];
        assertThat(Arrays.stream(active).max().orElseThrow())
                .as("ordinary-user partition skew; declared hot fixture excluded")
                .isLessThanOrEqualTo(2L * median);
        assertThat(Arrays.stream(hotCounts).sum()).isGreaterThan(0L);
    }

    private static long consumerLag(Admin admin, String topic) throws Exception {
        Map<TopicPartition, OffsetSpec> requests = new HashMap<>();
        for (int partition = 0; partition < PARTITIONS; partition++)
            requests.put(new TopicPartition(topic, partition), OffsetSpec.latest());
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                admin.listOffsets(requests).all().get(10, TimeUnit.SECONDS);
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
                admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(10, TimeUnit.SECONDS);
        return ends.entrySet().stream().mapToLong(entry -> entry.getValue().offset()
                - (committed.containsKey(entry.getKey()) ? committed.get(entry.getKey()).offset() : 0L)).sum();
    }

    private static void createTopic(String topic) throws Exception {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1)))
                    .all().get(20, TimeUnit.SECONDS);
        }
    }

    private static Admin admin() { return Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers())); }

    private static KafkaProducer<String, String> producer() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.LINGER_MS_CONFIG, "2");
        properties.put(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(256 * 1024));
        properties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        return new KafkaProducer<>(properties);
    }

    private static String eventJson(int sequence, int user, long timestamp) {
        return "{\"eventId\":\"load-" + sequence + "\",\"userId\":" + user
                + ",\"movieId\":" + (sequence % 10_000 + 1)
                + ",\"eventType\":\"click\",\"eventTimeMillis\":" + timestamp + "}";
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100L);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    static final class LoadSink<T> extends RichSinkFunction<T> {
        private static final AtomicLong PROCESSED = new AtomicLong();
        private static final AtomicLong ORDER_VIOLATIONS = new AtomicLong();
        private static final AtomicLong SNAPSHOTS = new AtomicLong();
        private static final Map<Integer, Long> LAST_EVENT_TIME = new ConcurrentHashMap<>();
        private final boolean snapshot;

        LoadSink(boolean snapshot) { this.snapshot = snapshot; }

        @Override public void invoke(T value, Context context) {
            if (snapshot) { SNAPSHOTS.incrementAndGet(); return; }
            MovieEvent event = (MovieEvent) value;
            LAST_EVENT_TIME.compute(event.userId, (user, previous) -> {
                if (previous != null && event.eventTimeMillis <= previous) ORDER_VIOLATIONS.incrementAndGet();
                return event.eventTimeMillis;
            });
            PROCESSED.incrementAndGet();
        }

        static long processed() { return PROCESSED.get(); }
        static long orderViolations() { return ORDER_VIOLATIONS.get(); }
        static long snapshots() { return SNAPSHOTS.get(); }
        static void clear() {
            PROCESSED.set(0L); ORDER_VIOLATIONS.set(0L); SNAPSHOTS.set(0L); LAST_EVENT_TIME.clear();
        }
    }
}
