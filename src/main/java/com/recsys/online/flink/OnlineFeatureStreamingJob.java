package com.recsys.online.flink;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.accumulators.LongCounter;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.StringUtils;
import org.apache.kafka.clients.admin.Admin;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;

import java.io.IOException;
import java.time.Duration;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public final class OnlineFeatureStreamingJob {
    private static final Logger LOG = LoggerFactory.getLogger(OnlineFeatureStreamingJob.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private OnlineFeatureStreamingJob() {}

    public static void main(String[] args) throws Exception {
        ParameterTool params = ParameterTool.fromArgs(args);
        JobConfiguration jobConfiguration = validateConfiguration(params);

        String redisHost = params.get("redis.host", "localhost");
        int redisPort = params.getInt("redis.port", 6379);
        int recentMovieLimit = params.getInt("recent-movie-limit", 3);
        int topK = params.getInt("top-k", 10);
        int topKBucketCount = params.getInt("top-k-bucket-count", jobConfiguration.operatorParallelism());
        int finalTopKParallelism = params.getInt("final-top-k-parallelism", 1);
        long allowedLatenessMs = validateAllowedLatenessMs(
                params.getLong("top-k-allowed-lateness-ms", 5_000L));
        long watermarkIdleTimeoutMs = validateWatermarkIdleTimeoutMs(
                params.getLong("watermark-idle-timeout-ms", 30_000L));
        if (topKBucketCount <= 0) throw new IllegalArgumentException("top-k-bucket-count must be positive");
        if (finalTopKParallelism <= 0) throw new IllegalArgumentException("final-top-k-parallelism must be positive");
        long windowSeconds = params.getLong("window-seconds", 10L);
        String windowLabel = params.get("window-label", "last_hour");
        int userHistoryTtlSeconds = params.getInt("user-history-ttl-seconds", 86400);
        int metricTtlSeconds = params.getInt("metric-ttl-seconds", 3600);
        int userEmbeddingTtlSeconds = params.getInt("user-embedding-ttl-seconds", 86400);
        int userEmbeddingDimensions = params.getInt("user-embedding-dimensions", 16);
        int sessionTtlSeconds = params.getInt("session-ttl-seconds", 1800);
        long idempotencyTtlSeconds = params.getLong("idempotency-ttl-seconds", 86400L);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(Math.max(5_000L, windowSeconds * 1_000L));
        String checkpointDir = params.get("checkpoint-dir", System.getenv("FLINK_CHECKPOINT_DIR"));
        if (checkpointDir != null && !checkpointDir.isBlank()) {
            env.setStateBackend(new EmbeddedRocksDBStateBackend(true));
            env.getCheckpointConfig().setCheckpointStorage(checkpointDir);
        }

        String bootstrapServers = params.get("bootstrap.servers");
        validateKafkaTopic(params, jobConfiguration);

        DataStream<MovieEvent> events = buildEventStream(env, params, jobConfiguration)
                .filter(OnlineFeatureStreamingJob::requiresEventIdentity)
                .keyBy(event -> event.userId)
                .process(new DeduplicateEventsFunction(idempotencyTtlSeconds))
                .name("event-idempotency")
                .uid("event-idempotency-v1")
                .setParallelism(jobConfiguration.operatorParallelism())
                .setMaxParallelism(jobConfiguration.maxParallelism())
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<MovieEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                                .withTimestampAssigner((event, timestamp) -> event.eventTimeMillis)
                                .withIdleness(Duration.ofMillis(watermarkIdleTimeoutMs)));

        boolean bridgeMode = params.getBoolean("bridge-mode", false);
        DataStream<UserRecentMoviesUpdate> recentUpdates = events
                .filter(MovieEvent::updatesRecentHistory)
                .keyBy(event -> event.userId)
                .process(new RecentMoviesFunction(recentMovieLimit, userHistoryTtlSeconds))
                .name("recent-movies")
                .uid("recent-movies-v1")
                .setParallelism(jobConfiguration.operatorParallelism())
                .setMaxParallelism(jobConfiguration.maxParallelism());
        attachSink(recentUpdates, new RedisRecentMoviesSink(redisHost, redisPort), bridgeMode,
                "redis-user-history-sink", "redis-user-history-sink-v1", jobConfiguration);

        DataStream<StringFeatureUpdate> embeddingUpdates = events
                .filter(MovieEvent::updatesRecentHistory)
                .keyBy(event -> event.userId)
                .process(new UserEmbeddingFunction(userEmbeddingDimensions, userEmbeddingTtlSeconds))
                .name("user-embedding-feature")
                .uid("user-embedding-feature-v1").setParallelism(jobConfiguration.operatorParallelism())
                .setMaxParallelism(jobConfiguration.maxParallelism());
        attachSink(embeddingUpdates, new RedisStringFeatureSink(redisHost, redisPort), bridgeMode,
                "redis-user-embedding-sink", "redis-user-embedding-sink-v1", jobConfiguration);

        DataStream<StringFeatureUpdate> sessionUpdates = events
                .filter(MovieEvent::hasSessionIdentity)
                .keyBy(event -> event.userId + "|" + event.sessionId())
                .process(new SessionFeatureFunction(sessionTtlSeconds))
                .name("session-feature")
                .uid("session-feature-v1").setParallelism(jobConfiguration.operatorParallelism())
                .setMaxParallelism(jobConfiguration.maxParallelism());
        attachSink(sessionUpdates, new RedisStringFeatureSink(redisHost, redisPort), bridgeMode,
                "redis-session-feature-sink", "redis-session-feature-sink-v1", jobConfiguration);

        DataStream<MovieMetricUpdate> metricUpdates = events
                .filter(event -> metricKind(event) != null)
                .keyBy(event -> event.movieId + "|" + metricKind(event))
                .window(TumblingProcessingTimeWindows.of(Time.seconds(windowSeconds)))
                .aggregate(new CountAggregate(), new MovieMetricWindowFunction(windowLabel, metricTtlSeconds))
                .name("movie-metrics")
                .uid("movie-metrics-v1").setParallelism(jobConfiguration.operatorParallelism())
                .setMaxParallelism(jobConfiguration.maxParallelism());
        attachSink(metricUpdates, new RedisMovieMetricSink(redisHost, redisPort), bridgeMode,
                "redis-movie-metric-sink", "redis-movie-metric-sink-v1", jobConfiguration);

        DataStream<PartialTopK> partials = events
                .filter(event -> event.engagementWeight() > 0L)
                .keyBy(event -> movieBucket(event.movieId, topKBucketCount))
                .window(TumblingEventTimeWindows.of(Time.seconds(windowSeconds)))
                .apply(new PartialTopKWindowFunction(topK))
                .name("topk-partial").uid("topk-partial-v1")
                .setParallelism(jobConfiguration.operatorParallelism())
                .setMaxParallelism(jobConfiguration.maxParallelism());

        DataStream<TopKSnapshot> topKSnapshots = partials
                .keyBy(PartialTopK::windowEnd)
                .process(new FinalTopKWindowFunction(topK, windowLabel, metricTtlSeconds,
                        topKBucketCount, allowedLatenessMs))
                .name("topk-final").uid("topk-final-v1")
                .setParallelism(Math.min(finalTopKParallelism, jobConfiguration.operatorParallelism()))
                .setMaxParallelism(jobConfiguration.maxParallelism());

        attachSink(topKSnapshots, new RedisTopKSink(redisHost, redisPort), bridgeMode,
                "redis-topk-sink", "redis-topk-sink-v1", jobConfiguration);
        attachSink(topKSnapshots, new RedisTrendFeatureSink(redisHost, redisPort), bridgeMode,
                "redis-trend-feature-sink", "redis-trend-feature-sink-v1", jobConfiguration);

        env.execute("recsys-online-feature-streaming");
    }

    private static boolean requiresEventIdentity(MovieEvent e) {
        if (e.userId <= 0) {
            LOG.warn("Dropping event with invalid userId: movieId={} type={}", e.movieId, e.eventType);
            return false;
        }
        if (e.hasEventIdentity()) return true;
        LOG.warn("Dropping event missing eventId — cannot deduplicate safely: userId={} movieId={} type={}",
                e.userId, e.movieId, e.eventType);
        return false;
    }

    static JobConfiguration validateConfiguration(ParameterTool params) {
        boolean kafka = !StringUtils.isNullOrWhitespaceOnly(params.get("bootstrap.servers"));
        boolean bridge = params.getBoolean("bridge-mode", false);
        String topic = params.get("topic", "movie_events_v2");
        String checkpointDir = params.get("checkpoint-dir", System.getenv("FLINK_CHECKPOINT_DIR"));
        if (bridge && !kafka) throw new IllegalArgumentException("bridge-mode requires bootstrap.servers");
        if (bridge && (StringUtils.isNullOrWhitespaceOnly(params.get("topic")) || "movie_events_v2".equals(topic))) {
            throw new IllegalArgumentException("bridge-mode requires an explicit legacy --topic");
        }
        if (kafka && !bridge && !"movie_events_v2".equals(topic)) {
            throw new IllegalArgumentException("this artifact only supports topic movie_events_v2; use bridge-mode for legacy replay");
        }
        if (kafka && StringUtils.isNullOrWhitespaceOnly(checkpointDir)) {
            throw new IllegalArgumentException("Kafka normal and bridge modes require a durable checkpoint-dir URI");
        }
        if (kafka && !params.getBoolean("allow-local-checkpoint-storage", false)) {
            validateDurableCheckpointUri(checkpointDir);
        }
        JobConfiguration configuration = new JobConfiguration(
                params.getInt("expected-topic-partitions", 24),
                params.getInt("source-parallelism", 24),
                params.getInt("operator-parallelism", 24),
                params.getInt("max-parallelism", 128));
        if (configuration.expectedTopicPartitions() <= 0) throw new IllegalArgumentException("expected-topic-partitions must be positive");
        if (configuration.sourceParallelism() <= 0) throw new IllegalArgumentException("source-parallelism must be positive");
        if (configuration.operatorParallelism() <= 0) throw new IllegalArgumentException("operator-parallelism must be positive");
        if (configuration.maxParallelism() <= 0) throw new IllegalArgumentException("max-parallelism must be positive");
        if (configuration.sourceParallelism() > configuration.expectedTopicPartitions()) {
            throw new IllegalArgumentException("source-parallelism cannot exceed expected-topic-partitions");
        }
        if (configuration.maxParallelism() < configuration.sourceParallelism()
                || configuration.maxParallelism() < configuration.operatorParallelism()) {
            throw new IllegalArgumentException(
                    "max-parallelism cannot be below source-parallelism or operator-parallelism");
        }
        return configuration;
    }

    private static final Set<String> DURABLE_CHECKPOINT_SCHEMES =
            Set.of("s3", "s3a", "hdfs", "gs", "abfs", "abfss", "wasb", "wasbs");

    static void validateDurableCheckpointUri(String checkpointDir) {
        try {
            URI uri = URI.create(checkpointDir.trim());
            if (uri.getScheme() == null || !DURABLE_CHECKPOINT_SCHEMES.contains(uri.getScheme().toLowerCase())) {
                throw new IllegalArgumentException("checkpoint-dir must use shared durable storage; local/file/tmp paths require the explicit local-test override");
            }
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException && e.getMessage().contains("shared durable")) throw e;
            throw new IllegalArgumentException("checkpoint-dir must be a valid shared durable URI", e);
        }
    }

    static <T> void attachSink(DataStream<T> stream, RichSinkFunction<T> productionSink,
                                       boolean bridgeMode, String name, String uid,
                                       JobConfiguration configuration) {
        RichSinkFunction<T> sink = bridgeMode ? new NoOpStateSink<>() : productionSink;
        stream.addSink(sink)
                .name(bridgeMode ? "bridge-noop-" + name : name).uid(uid)
                .setParallelism(configuration.operatorParallelism())
                .setMaxParallelism(configuration.maxParallelism());
    }

    record JobConfiguration(int expectedTopicPartitions, int sourceParallelism,
                            int operatorParallelism, int maxParallelism) {}

    static void validateKafkaTopic(ParameterTool params, JobConfiguration configuration) throws Exception {
        String bootstrapServers = params.get("bootstrap.servers");
        if (StringUtils.isNullOrWhitespaceOnly(bootstrapServers)) return;
        Properties adminProperties = kafkaProperties(params);
        adminProperties.setProperty("bootstrap.servers", bootstrapServers);
        try (Admin admin = Admin.create(adminProperties)) {
            KafkaTopicPartitionValidator.validate(admin, params.get("topic", "movie_events_v2"),
                    configuration.expectedTopicPartitions());
        }
    }

    static DataStream<MovieEvent> buildEventStream(StreamExecutionEnvironment env,
                                                           ParameterTool params,
                                                           JobConfiguration configuration) throws IOException {
        String bootstrapServers = params.get("bootstrap.servers");
        String topic = params.get("topic", "movie_events_v2");

        if (!StringUtils.isNullOrWhitespaceOnly(bootstrapServers)) {
            KafkaSource<String> source = KafkaSource.<String>builder()
                    .setBootstrapServers(bootstrapServers)
                    .setTopics(topic)
                    .setGroupId(params.get("group.id", params.getBoolean("bridge-mode", false)
                            ? "online-features-bridge-v1" : "online-features-v2"))
                    .setStartingOffsets(OffsetsInitializer.earliest())
                    .setValueOnlyDeserializer(new SimpleStringSchema())
                    .setProperties(kafkaProperties(params))
                    .build();

            String sourceUid = params.getBoolean("bridge-mode", false)
                    ? "kafka-movie-events-bridge-v1" : "kafka-movie-events-v2";
            return env.fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-movie-events")
                    .uid(sourceUid)
                    .setParallelism(configuration.sourceParallelism())
                    .setMaxParallelism(configuration.maxParallelism())
                    .flatMap((String line, Collector<MovieEvent> out) -> {
                        MovieEvent e = parseEvent(line);
                        if (e != null) out.collect(e);
                    }).returns(MovieEvent.class);
        }

        String inputFile = params.get("input-file", "streaming/online-serving/data/movie_events.ndjson");
        return env.readTextFile(inputFile)
                .uid("kafka-movie-events-v2")
                .setParallelism(configuration.sourceParallelism())
                .setMaxParallelism(configuration.maxParallelism())
                .filter(line -> !line.isBlank())
                .flatMap((String line, Collector<MovieEvent> out) -> {
                    MovieEvent e = parseEvent(line);
                    if (e != null) out.collect(e);
                }).returns(MovieEvent.class);
    }

    /** Narrow graph seam used by the Kafka/MiniCluster contract test. */
    static PartitionGraph buildPartitionGraph(DataStream<MovieEvent> input, JobConfiguration configuration,
                                               int topK, int bucketCount, long windowMillis,
                                               long allowedLatenessMillis, long idleMillis) {
        DataStream<MovieEvent> events = input
                .filter(OnlineFeatureStreamingJob::requiresEventIdentity)
                .keyBy(event -> event.userId)
                .process(new DeduplicateEventsFunction(3_600))
                .name("event-idempotency").uid("event-idempotency-v1")
                .setParallelism(configuration.operatorParallelism())
                .setMaxParallelism(configuration.maxParallelism())
                .assignTimestampsAndWatermarks(WatermarkStrategy.<MovieEvent>forBoundedOutOfOrderness(Duration.ZERO)
                        .withTimestampAssigner((event, timestamp) -> event.eventTimeMillis)
                        .withIdleness(Duration.ofMillis(idleMillis)));
        DataStream<PartialTopK> partials = events.filter(event -> event.engagementWeight() > 0L)
                .keyBy(event -> movieBucket(event.movieId, bucketCount))
                .window(TumblingEventTimeWindows.of(Time.milliseconds(windowMillis)))
                .apply(new PartialTopKWindowFunction(topK))
                .name("topk-partial").uid("topk-partial-v1")
                .setParallelism(configuration.operatorParallelism())
                .setMaxParallelism(configuration.maxParallelism());
        DataStream<TopKSnapshot> snapshots = partials.keyBy(PartialTopK::windowEnd)
                .process(new FinalTopKWindowFunction(topK, "integration", 60, bucketCount,
                        allowedLatenessMillis))
                .name("topk-final").uid("topk-final-v1")
                .setParallelism(configuration.operatorParallelism())
                .setMaxParallelism(configuration.maxParallelism());
        return new PartitionGraph(events, snapshots);
    }

    record PartitionGraph(DataStream<MovieEvent> events, DataStream<TopKSnapshot> snapshots) {}

    private static Properties kafkaProperties(ParameterTool params) {
        Properties properties = new Properties();
        properties.setProperty("max.poll.records", params.get("mq.max-poll-records", "500"));
        properties.setProperty("fetch.min.bytes", params.get("mq.fetch-min-bytes", "1024"));
        properties.setProperty("fetch.max.wait.ms", params.get("mq.fetch-max-wait-ms", "500"));
        for (Map.Entry<String, String> entry : params.toMap().entrySet()) {
            if (entry.getKey().startsWith("kafka.")) {
                properties.setProperty(entry.getKey().substring("kafka.".length()), entry.getValue());
            }
        }
        return properties;
    }

    private static MovieEvent parseEvent(String json) {
        try {
            return MAPPER.readValue(json, MovieEvent.class);
        } catch (IOException e) {
            LOG.warn("Skipping malformed movie event JSON: {}", json, e);
            return null;
        }
    }

    private static String metricKind(MovieEvent event) {
        if (event.movieId <= 0) {
            return null;
        }
        if (event.isOrder()) {
            return "orders_1h";
        }
        if (event.isLike()) {
            return "likes_1h";
        }
        if (event.isRating()) {
            return "ratings_1h";
        }
        if (event.isClick()) {
            return "clicks_1h";
        }
        if (event.isDwell()) {
            return "dwells_1h";
        }
        if (event.isView()) {
            return "views_1h";
        }
        return null;
    }

    static int movieBucket(int movieId, int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        return Math.floorMod(Integer.hashCode(movieId), bucketCount);
    }

    static StateTtlConfig stateTtl(long ttlSeconds) {
        return StateTtlConfig.newBuilder(
                        org.apache.flink.api.common.time.Time.seconds(Math.max(1L, ttlSeconds)))
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                .cleanupInRocksdbCompactFilter(1_000L)
                .build();
    }

    static long validateAllowedLatenessMs(long allowedLatenessMs) {
        if (allowedLatenessMs < 0L) {
            throw new IllegalArgumentException("top-k-allowed-lateness-ms must be non-negative");
        }
        return allowedLatenessMs;
    }

    static long validateWatermarkIdleTimeoutMs(long watermarkIdleTimeoutMs) {
        if (watermarkIdleTimeoutMs <= 0L) {
            throw new IllegalArgumentException("watermark-idle-timeout-ms must be positive");
        }
        return watermarkIdleTimeoutMs;
    }

    static final class RecentMoviesFunction extends KeyedProcessFunction<Integer, MovieEvent, UserRecentMoviesUpdate> {
        private final int limit;
        private final int ttlSeconds;
        private transient ListState<Integer> recentMoviesState;

        RecentMoviesFunction(int limit, int ttlSeconds) {
            this.limit = limit;
            this.ttlSeconds = ttlSeconds;
        }

        @Override
        public void open(Configuration parameters) {
            ListStateDescriptor<Integer> descriptor = new ListStateDescriptor<>("recent-movie-ids", Types.INT);
            descriptor.enableTimeToLive(stateTtl(ttlSeconds));
            recentMoviesState = getRuntimeContext().getListState(descriptor);
        }

        @Override
        public void processElement(MovieEvent event,
                                   KeyedProcessFunction<Integer, MovieEvent, UserRecentMoviesUpdate>.Context context,
                                   Collector<UserRecentMoviesUpdate> out) throws Exception {
            Deque<Integer> movies = new ArrayDeque<>();
            for (Integer movieId : recentMoviesState.get()) {
                movies.addLast(movieId);
            }

            movies.remove(event.movieId);
            movies.addLast(event.movieId);
            while (movies.size() > limit) {
                movies.removeFirst();
            }

            recentMoviesState.update(new ArrayList<>(movies));
            out.collect(new UserRecentMoviesUpdate(
                    "user:" + event.userId + ":recent_movies",
                    joinMovieIds(movies),
                    event.eventTimeMillis,
                    ttlSeconds,
                    event.eventId
            ));
        }

        private static String joinMovieIds(Deque<Integer> movieIds) {
            return movieIds.stream().map(Object::toString).collect(Collectors.joining(" "));
        }
    }

    static final class UserEmbeddingFunction extends KeyedProcessFunction<Integer, MovieEvent, StringFeatureUpdate> {
        private final int dimensions;
        private final int ttlSeconds;
        private transient ValueState<UserEmbeddingState> state;

        UserEmbeddingFunction(int dimensions, int ttlSeconds) {
            this.dimensions = Math.max(1, dimensions);
            this.ttlSeconds = ttlSeconds;
        }

        @Override
        public void open(Configuration parameters) {
            ValueStateDescriptor<UserEmbeddingState> descriptor =
                    new ValueStateDescriptor<>("user-embedding-feature", UserEmbeddingState.class);
            descriptor.enableTimeToLive(stateTtl(ttlSeconds));
            state = getRuntimeContext().getState(descriptor);
        }

        @Override
        public void processElement(MovieEvent event,
                                   KeyedProcessFunction<Integer, MovieEvent, StringFeatureUpdate>.Context context,
                                   Collector<StringFeatureUpdate> out) throws Exception {
            UserEmbeddingState current = state.value();
            double[] vector = current == null ? new double[dimensions] : parseVector(current.vector, dimensions);
            int bucket = Math.floorMod(event.movieId, dimensions);
            vector[bucket] += Math.max(1L, event.engagementWeight());

            // Store raw counts in state so accumulation is always on the same scale.
            String rawEncoded = encodeRaw(vector);
            UserEmbeddingState next = new UserEmbeddingState();
            next.vector = rawEncoded;                   // raw counts, not normalised
            next.updatedAtMillis = event.eventTimeMillis;
            state.update(next);

            // Normalise only for the Redis output so the serving layer gets a unit vector.
            out.collect(new StringFeatureUpdate(
                    "u2vEmb:" + event.userId,
                    encodeVector(vector),               // normalised for Redis
                    event.eventTimeMillis,
                    ttlSeconds,
                    event.eventId
            ));
        }

        private static double[] parseVector(String encoded, int dimensions) {
            double[] vector = new double[dimensions];
            if (encoded == null || encoded.isBlank()) {
                return vector;
            }
            String[] parts = encoded.split("\\s+");
            for (int i = 0; i < Math.min(parts.length, dimensions); i++) {
                try {
                    vector[i] = Double.parseDouble(parts[i]);
                } catch (NumberFormatException ignore) {
                    vector[i] = 0.0;
                }
            }
            return vector;
        }

        private static String encodeVector(double[] vector) {
            double norm = 0.0;
            for (double v : vector) {
                norm += v * v;
            }
            norm = Math.sqrt(norm);
            StringBuilder builder = new StringBuilder(vector.length * 6);
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) builder.append(' ');
                double value = norm > 0.0 ? vector[i] / norm : 0.0;
                builder.append(String.format(java.util.Locale.ROOT, "%.6f", value));
            }
            return builder.toString();
        }

        static String encodeRaw(double[] vector) {
            StringBuilder builder = new StringBuilder(vector.length * 10);
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) builder.append(' ');
                builder.append(String.format(java.util.Locale.ROOT, "%.6f", vector[i]));
            }
            return builder.toString();
        }
    }

    static final class SessionFeatureFunction extends KeyedProcessFunction<String, MovieEvent, StringFeatureUpdate> {
        private final int ttlSeconds;
        private transient ValueState<SessionFeatureState> state;

        SessionFeatureFunction(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        @Override
        public void open(Configuration parameters) {
            ValueStateDescriptor<SessionFeatureState> descriptor =
                    new ValueStateDescriptor<>("session-feature", SessionFeatureState.class);
            descriptor.enableTimeToLive(stateTtl(ttlSeconds));
            state = getRuntimeContext().getState(descriptor);
        }

        @Override
        public void processElement(MovieEvent event,
                                   KeyedProcessFunction<String, MovieEvent, StringFeatureUpdate>.Context context,
                                   Collector<StringFeatureUpdate> out) throws Exception {
            SessionFeatureState current = state.value();
            if (current == null) {
                current = new SessionFeatureState();
                current.userId = event.userId;
                current.sessionId = event.sessionId();
            }
            current.eventCount++;
            if (event.isClick()) current.clickCount++;
            if (event.isView()) current.watchCount++;
            if (event.isLike()) current.likeCount++;
            if (event.isSearch()) current.searchCount++;
            current.engagementScore += event.engagementWeight();
            current.lastMovieId = event.movieId;
            current.updatedAtMillis = event.eventTimeMillis;
            current.lastEventType = event.eventType == null ? "" : event.eventType;
            state.update(current);

            out.collect(new StringFeatureUpdate(
                    "feature:user:" + event.userId + ":session:" + current.sessionId,
                    current.encode(),
                    event.eventTimeMillis,
                    ttlSeconds,
                    event.eventId
            ));
        }
    }

    static final class DeduplicateEventsFunction extends KeyedProcessFunction<Integer, MovieEvent, MovieEvent> {
        private final long ttlSeconds;
        private transient MapState<String, Long> eventExpiry;

        DeduplicateEventsFunction(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        @Override
        public void open(Configuration parameters) {
            MapStateDescriptor<String, Long> descriptor =
                    new MapStateDescriptor<>("event-id-expiry", Types.STRING, Types.LONG);
            descriptor.enableTimeToLive(stateTtl(ttlSeconds));
            eventExpiry = getRuntimeContext().getMapState(descriptor);
        }

        @Override
        public void processElement(MovieEvent event,
                                   KeyedProcessFunction<Integer, MovieEvent, MovieEvent>.Context context,
                                   Collector<MovieEvent> out) throws Exception {
            long now = context.timerService().currentProcessingTime();
            Long expiry = eventExpiry.get(event.eventId);
            if (expiry != null && expiry > now) {
                LOG.debug("Skipping duplicate movie event: {}", event.eventId);
                return;
            }
            eventExpiry.put(event.eventId, now + Math.max(1L, ttlSeconds) * 1_000L);
            out.collect(event);
        }
    }

    static final class CountAggregate implements AggregateFunction<MovieEvent, Long, Long> {
        @Override
        public Long createAccumulator() {
            return 0L;
        }

        @Override
        public Long add(MovieEvent value, Long accumulator) {
            return accumulator + 1L;
        }

        @Override
        public Long getResult(Long accumulator) {
            return accumulator;
        }

        @Override
        public Long merge(Long a, Long b) {
            return a + b;
        }
    }

    static final class MovieMetricWindowFunction extends ProcessWindowFunction<Long, MovieMetricUpdate, String, TimeWindow> {
        private final String windowLabel;
        private final int ttlSeconds;

        MovieMetricWindowFunction(String windowLabel, int ttlSeconds) {
            this.windowLabel = windowLabel;
            this.ttlSeconds = ttlSeconds;
        }

        @Override
        public void process(String key,
                            ProcessWindowFunction<Long, MovieMetricUpdate, String, TimeWindow>.Context context,
                            Iterable<Long> elements,
                            Collector<MovieMetricUpdate> out) {
            java.util.Iterator<Long> it = elements.iterator();
            if (!it.hasNext()) return;
            long count = it.next();
            String[] parts = key.split("\\|", 2);
            int movieId = Integer.parseInt(parts[0]);
            String metric = parts[1];
            out.collect(new MovieMetricUpdate(
                    "movie:" + movieId + ":" + metric,
                    count,
                    context.window().getEnd(),
                    ttlSeconds,
                    movieId
            ));
        }
    }

    static final class PartialTopKWindowFunction
            implements WindowFunction<MovieEvent, PartialTopK, Integer, TimeWindow> {
        private final int topK;

        PartialTopKWindowFunction(int topK) {
            this.topK = topK;
        }

        @Override
        public void apply(Integer bucket, TimeWindow window, Iterable<MovieEvent> values,
                          Collector<PartialTopK> out) {
            Map<Integer, Long> scores = new HashMap<>();
            for (MovieEvent event : values) {
                scores.merge(event.movieId, event.engagementWeight(), Long::sum);
            }

            List<ScoredMovie> ranked = scores.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry::getKey))
                    .limit(topK)
                    .map(entry -> new ScoredMovie(entry.getKey(), entry.getValue()))
                    .collect(Collectors.toCollection(ArrayList::new));

            out.collect(new PartialTopK(window.getEnd(), bucket, ranked));
        }
    }

    static final class FinalTopKWindowFunction
            extends KeyedProcessFunction<Long, PartialTopK, TopKSnapshot> {
        private final int topK;
        private final String windowLabel;
        private final int ttlSeconds;
        private final int bucketCount;
        private final long allowedLatenessMs;
        private transient ListState<PartialTopK> partials;
        private transient ValueState<Long> emitTimer;
        private transient ValueState<Long> cleanupTimer;
        private transient ValueState<Boolean> emitted;
        private transient LongCounter latePartials;

        FinalTopKWindowFunction(int topK, String windowLabel, int ttlSeconds, int bucketCount,
                                long allowedLatenessMs) {
            this.topK = topK;
            this.windowLabel = windowLabel;
            this.ttlSeconds = ttlSeconds;
            this.bucketCount = bucketCount;
            this.allowedLatenessMs = validateAllowedLatenessMs(allowedLatenessMs);
        }

        @Override
        public void open(Configuration parameters) {
            partials = getRuntimeContext().getListState(
                    new ListStateDescriptor<>("topk-partials", PartialTopK.class));
            emitTimer = getRuntimeContext().getState(
                    new ValueStateDescriptor<>("topk-final-emit-timer", Types.LONG));
            cleanupTimer = getRuntimeContext().getState(
                    new ValueStateDescriptor<>("topk-final-cleanup-timer", Types.LONG));
            emitted = getRuntimeContext().getState(
                    new ValueStateDescriptor<>("topk-final-emitted", Types.BOOLEAN));
            latePartials = getRuntimeContext().getLongCounter("topk-late-partials");
        }

        @Override
        public void processElement(PartialTopK partial,
                                   KeyedProcessFunction<Long, PartialTopK, TopKSnapshot>.Context context,
                                   Collector<TopKSnapshot> out) throws Exception {
            long cleanupAt = cleanupTimestamp(partial.windowEnd, allowedLatenessMs);
            if (context.timerService().currentWatermark() >= cleanupAt) {
                rejectLatePartial(partial);
                return;
            }
            if (Boolean.TRUE.equals(emitted.value())) {
                rejectLatePartial(partial);
                return;
            }
            partials.add(partial);
            if (emitTimer.value() == null) {
                context.timerService().registerEventTimeTimer(partial.windowEnd);
                emitTimer.update(partial.windowEnd);
                context.timerService().registerEventTimeTimer(cleanupAt);
                cleanupTimer.update(cleanupAt);
            }
        }

        @Override
        public void onTimer(long timestamp,
                            KeyedProcessFunction<Long, PartialTopK, TopKSnapshot>.OnTimerContext context,
                            Collector<TopKSnapshot> out) throws Exception {
            Long cleanupAt = cleanupTimer.value();
            if (cleanupAt != null && timestamp == cleanupAt) {
                clearWindowState();
                return;
            }
            Long emitAt = emitTimer.value();
            if (emitAt == null || timestamp != emitAt || Boolean.TRUE.equals(emitted.value())) return;
            List<PartialTopK> available = new ArrayList<>();
            for (PartialTopK partial : partials.get()) available.add(partial);
            out.collect(new TopKSnapshot("topk:" + windowLabel, mergeTopK(available, topK),
                    context.getCurrentKey(), ttlSeconds));
            partials.clear();
            emitTimer.clear();
            emitted.update(true);
        }

        private void rejectLatePartial(PartialTopK partial) {
            latePartials.add(1L);
            LOG.warn("Rejecting late Top-K partial for completed window {} bucket {} of {}",
                    partial.windowEnd, partial.bucket, bucketCount);
        }

        private void clearWindowState() throws Exception {
            partials.clear();
            emitTimer.clear();
            cleanupTimer.clear();
            emitted.clear();
        }

        static long cleanupTimestamp(long windowEnd, long allowedLatenessMs) {
            long delay = Math.max(1L, allowedLatenessMs);
            return windowEnd > Long.MAX_VALUE - delay ? Long.MAX_VALUE : windowEnd + delay;
        }

        static List<ScoredMovie> mergeTopK(Iterable<PartialTopK> partials, int topK) {
            Map<Integer, Long> scores = new HashMap<>();
            for (PartialTopK partial : partials) {
                for (ScoredMovie movie : partial.movies) {
                    scores.merge(movie.movieId, movie.score, Long::sum);
                }
            }
            return scores.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry::getKey))
                    .limit(topK)
                    .map(entry -> new ScoredMovie(entry.getKey(), entry.getValue()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    abstract static class AbstractRedisSink<T> extends RichSinkFunction<T> {
        private static final String SET_IF_NEWER_SCRIPT = """
                local current = redis.call('GET', KEYS[2])
                if current and tonumber(current) > tonumber(ARGV[1]) then
                  return 0
                end
                redis.call('SETEX', KEYS[1], tonumber(ARGV[2]), ARGV[3])
                redis.call('SETEX', KEYS[2], tonumber(ARGV[2]), ARGV[1])
                return 1
                """;

        private static final String SET_IF_NEWER_WITH_LINEAGE_SCRIPT = """
                local current = redis.call('GET', KEYS[2])
                if current and tonumber(current) > tonumber(ARGV[1]) then
                  return 0
                end
                redis.call('SETEX', KEYS[1], tonumber(ARGV[2]), ARGV[3])
                redis.call('SETEX', KEYS[2], tonumber(ARGV[2]), ARGV[1])
                redis.call('SETEX', KEYS[3], tonumber(ARGV[2]), ARGV[4])
                redis.call('RPUSH', KEYS[4], ARGV[4])
                redis.call('LTRIM', KEYS[4], -5, -1)
                redis.call('EXPIRE', KEYS[4], tonumber(ARGV[2]))
                redis.call('SADD', KEYS[5], KEYS[1])
                redis.call('EXPIRE', KEYS[5], tonumber(ARGV[2]))
                return 1
                """;

        private static final String ZSET_IF_NEWER_SCRIPT = """
                local current = redis.call('GET', KEYS[2])
                if current and tonumber(current) > tonumber(ARGV[1]) then
                  return 0
                end
                redis.call('DEL', KEYS[1])
                for i = 3, #ARGV, 2 do
                  redis.call('ZADD', KEYS[1], tonumber(ARGV[i + 1]), ARGV[i])
                end
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
                redis.call('SETEX', KEYS[2], tonumber(ARGV[2]), ARGV[1])
                return 1
                """;

        private final String host;
        private final int port;
        transient RedisClient client;
        transient StatefulRedisConnection<String, String> connection;

        AbstractRedisSink(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public void open(Configuration parameters) {
            client = RedisClient.create(RedisURI.create(host, port));
            connection = client.connect(StringCodec.UTF8);
        }

        @Override
        public void close() {
            if (connection != null) connection.close();
            if (client != null) client.shutdown();
        }

        /** Sync command interface on the sink's long-lived connection. */
        RedisCommands<String, String> commands() {
            return connection.sync();
        }

        void setStringIfNewer(RedisCommands<String, String> cmd, String redisKey, String value,
                              long updatedAtMillis, int ttlSeconds) {
            cmd.eval(
                    SET_IF_NEWER_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{redisKey, redisKey + ":updated_at"},
                    Long.toString(updatedAtMillis), Integer.toString(ttlSeconds), value
            );
        }

        void setStringIfNewerWithLineage(RedisCommands<String, String> cmd, String redisKey, String value,
                                          long updatedAtMillis, int ttlSeconds, String eventId) {
            cmd.eval(
                    SET_IF_NEWER_WITH_LINEAGE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{redisKey,
                            redisKey + ":updated_at",
                            redisKey + ":last_event",
                            redisKey + ":event_history",
                            "lineage:event:" + eventId},
                    Long.toString(updatedAtMillis),
                    Integer.toString(ttlSeconds),
                    value,
                    eventId
            );
        }

        void setTopKIfNewer(RedisCommands<String, String> cmd, TopKSnapshot value) {
            List<String> args = new ArrayList<>(2 + value.movies.size() * 2);
            args.add(Long.toString(value.updatedAtMillis));
            args.add(Integer.toString(value.ttlSeconds));
            for (ScoredMovie movie : value.movies) {
                args.add(Integer.toString(movie.movieId));
                args.add(Long.toString(movie.score));
            }
            cmd.eval(
                    ZSET_IF_NEWER_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{value.redisKey, value.redisKey + ":updated_at"},
                    args.toArray(new String[0])
            );
        }
    }

    /** State-only bridge terminal: preserves sink UIDs without any external side effect. */
    static final class NoOpStateSink<T> extends RichSinkFunction<T> {
        @Override public void invoke(T value, Context context) { }
    }

    static final class RedisStringFeatureSink extends AbstractRedisSink<StringFeatureUpdate> {
        RedisStringFeatureSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(StringFeatureUpdate value, Context context) {
            setStringIfNewerWithLineage(commands(), value.redisKey, value.value,
                    value.updatedAtMillis, value.ttlSeconds, value.eventId);
        }
    }

    static final class RedisRecentMoviesSink extends AbstractRedisSink<UserRecentMoviesUpdate> {
        RedisRecentMoviesSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(UserRecentMoviesUpdate value, Context context) {
            setStringIfNewerWithLineage(commands(), value.redisKey, value.value,
                    value.updatedAtMillis, value.ttlSeconds, value.eventId);
        }
    }

    static final class RedisMovieMetricSink extends AbstractRedisSink<MovieMetricUpdate> {
        RedisMovieMetricSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(MovieMetricUpdate value, Context context) {
            setStringIfNewer(commands(), value.redisKey, Long.toString(value.count),
                    value.updatedAtMillis, value.ttlSeconds);
        }
    }

    static final class RedisTopKSink extends AbstractRedisSink<TopKSnapshot> {
        RedisTopKSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(TopKSnapshot value, Context context) {
            RedisCommands<String, String> cmd = commands();
            setTopKIfNewer(cmd, value);
            setTopKIfNewer(cmd, value.withRedisKey(value.redisKey.replace("topk:", "feature:hot_movies:")));
        }
    }

    static final class RedisTrendFeatureSink extends AbstractRedisSink<TopKSnapshot> {
        RedisTrendFeatureSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(TopKSnapshot value, Context context) {
            setStringIfNewer(commands(), value.redisKey.replace("topk:", "feature:trend:"),
                    value.encodeTrend(), value.updatedAtMillis, value.ttlSeconds);
        }
    }

    static final class UserRecentMoviesUpdate {
        final String redisKey;
        final String value;
        final long updatedAtMillis;
        final int ttlSeconds;
        final String eventId;

        UserRecentMoviesUpdate(String redisKey, String value, long updatedAtMillis,
                               int ttlSeconds, String eventId) {
            this.redisKey = redisKey;
            this.value = value;
            this.updatedAtMillis = updatedAtMillis;
            this.ttlSeconds = ttlSeconds;
            this.eventId = eventId;
        }
    }

    static final class MovieMetricUpdate {
        final String redisKey;
        final long count;
        final long updatedAtMillis;
        final int ttlSeconds;
        final int movieId;

        MovieMetricUpdate(String redisKey, long count, long updatedAtMillis, int ttlSeconds, int movieId) {
            this.redisKey = redisKey;
            this.count = count;
            this.updatedAtMillis = updatedAtMillis;
            this.ttlSeconds = ttlSeconds;
            this.movieId = movieId;
        }
    }

    static final class StringFeatureUpdate {
        final String redisKey;
        final String value;
        final long updatedAtMillis;
        final int ttlSeconds;
        final String eventId;

        StringFeatureUpdate(String redisKey, String value, long updatedAtMillis,
                            int ttlSeconds, String eventId) {
            this.redisKey = redisKey;
            this.value = value;
            this.updatedAtMillis = updatedAtMillis;
            this.ttlSeconds = ttlSeconds;
            this.eventId = eventId;
        }
    }

    public static final class UserEmbeddingState {
        public String vector = "";
        public long updatedAtMillis;
    }

    public static final class SessionFeatureState {
        public int userId;
        public String sessionId = "";
        public long eventCount;
        public long clickCount;
        public long watchCount;
        public long likeCount;
        public long searchCount;
        public long engagementScore;
        public int lastMovieId;
        public String lastEventType = "";
        public long updatedAtMillis;

        String encode() {
            return "eventCount=" + eventCount
                    + ",clicks=" + clickCount
                    + ",watches=" + watchCount
                    + ",likes=" + likeCount
                    + ",searches=" + searchCount
                    + ",engagementScore=" + engagementScore
                    + ",lastMovieId=" + lastMovieId
                    + ",lastEventType=" + lastEventType;
        }
    }

    public static final class ScoredMovie {
        public int movieId;
        public long score;

        public ScoredMovie() {}

        ScoredMovie(int movieId, long score) {
            this.movieId = movieId;
            this.score = score;
        }
    }

    public static final class PartialTopK {
        public long windowEnd;
        public int bucket;
        public List<ScoredMovie> movies;

        public PartialTopK() {
            this.movies = new ArrayList<>();
        }

        PartialTopK(long windowEnd, int bucket, List<ScoredMovie> movies) {
            this.windowEnd = windowEnd;
            this.bucket = bucket;
            this.movies = movies;
        }

        long windowEnd() {
            return windowEnd;
        }
    }

    public static final class TopKSnapshot {
        public String redisKey;
        public List<ScoredMovie> movies;
        public long updatedAtMillis;
        public int ttlSeconds;

        public TopKSnapshot() {
            this.movies = new ArrayList<>();
        }

        TopKSnapshot(String redisKey, List<ScoredMovie> movies, long updatedAtMillis, int ttlSeconds) {
            this.redisKey = redisKey;
            this.movies = movies;
            this.updatedAtMillis = updatedAtMillis;
            this.ttlSeconds = ttlSeconds;
        }

        TopKSnapshot withRedisKey(String nextRedisKey) {
            return new TopKSnapshot(nextRedisKey, movies, updatedAtMillis, ttlSeconds);
        }

        String encodeTrend() {
            return movies.stream()
                    .map(movie -> movie.movieId + ":" + movie.score)
                    .collect(Collectors.joining(","));
        }
    }
}
