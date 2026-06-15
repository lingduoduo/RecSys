package com.recsys.online.flink;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
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
import org.apache.flink.streaming.api.functions.windowing.AllWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.StringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public final class OnlineFeatureStreamingJob {
    private static final Logger LOG = LoggerFactory.getLogger(OnlineFeatureStreamingJob.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private OnlineFeatureStreamingJob() {}

    public static void main(String[] args) throws Exception {
        ParameterTool params = ParameterTool.fromArgs(args);

        String redisHost = params.get("redis.host", "localhost");
        int redisPort = params.getInt("redis.port", 6379);
        int recentMovieLimit = params.getInt("recent-movie-limit", 3);
        int topK = params.getInt("top-k", 10);
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

        DataStream<MovieEvent> events = buildEventStream(env, params)
                .filter(OnlineFeatureStreamingJob::requiresEventIdentity)
                .keyBy(MovieEvent::idempotencyKey)
                .process(new DeduplicateEventsFunction(idempotencyTtlSeconds))
                .name("event-idempotency")
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<MovieEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                                .withTimestampAssigner((event, timestamp) -> event.eventTimeMillis));

        events
                .filter(MovieEvent::updatesRecentHistory)
                .keyBy(event -> event.userId)
                .process(new RecentMoviesFunction(recentMovieLimit, userHistoryTtlSeconds))
                .name("recent-movies")
                .addSink(new RedisRecentMoviesSink(redisHost, redisPort))
                .name("redis-user-history-sink");

        events
                .filter(MovieEvent::updatesRecentHistory)
                .keyBy(event -> event.userId)
                .process(new UserEmbeddingFunction(userEmbeddingDimensions, userEmbeddingTtlSeconds))
                .name("user-embedding-feature")
                .addSink(new RedisStringFeatureSink(redisHost, redisPort))
                .name("redis-user-embedding-sink");

        events
                .filter(MovieEvent::hasSessionIdentity)
                .keyBy(event -> event.userId + "|" + event.sessionId())
                .process(new SessionFeatureFunction(sessionTtlSeconds))
                .name("session-feature")
                .addSink(new RedisStringFeatureSink(redisHost, redisPort))
                .name("redis-session-feature-sink");

        events
                .filter(event -> metricKind(event) != null)
                .keyBy(event -> event.movieId + "|" + metricKind(event))
                .window(TumblingProcessingTimeWindows.of(Time.seconds(windowSeconds)))
                .aggregate(new CountAggregate(), new MovieMetricWindowFunction(windowLabel, metricTtlSeconds))
                .name("movie-metrics")
                .addSink(new RedisMovieMetricSink(redisHost, redisPort))
                .name("redis-movie-metric-sink");

        DataStream<TopKSnapshot> topKSnapshots = events
                .filter(event -> event.engagementWeight() > 0L)
                .windowAll(TumblingProcessingTimeWindows.of(Time.seconds(windowSeconds)))
                .apply(new TopKAllWindowFunction(topK, windowLabel, metricTtlSeconds))
                .name("topk-window");

        topKSnapshots
                .addSink(new RedisTopKSink(redisHost, redisPort))
                .name("redis-topk-sink");

        topKSnapshots
                .addSink(new RedisTrendFeatureSink(redisHost, redisPort))
                .name("redis-trend-feature-sink");

        env.execute("recsys-online-feature-streaming");
    }

    private static boolean requiresEventIdentity(MovieEvent e) {
        if (e.hasEventIdentity()) return true;
        LOG.warn("Dropping event missing eventId — cannot deduplicate safely: userId={} movieId={} type={}",
                e.userId, e.movieId, e.eventType);
        return false;
    }

    private static DataStream<MovieEvent> buildEventStream(StreamExecutionEnvironment env,
                                                           ParameterTool params) throws IOException {
        String bootstrapServers = params.get("bootstrap.servers");
        String topic = params.get("topic", "recsys_events");

        if (!StringUtils.isNullOrWhitespaceOnly(bootstrapServers)) {
            KafkaSource<String> source = KafkaSource.<String>builder()
                    .setBootstrapServers(bootstrapServers)
                    .setTopics(topic)
                    .setGroupId(params.get("group.id", "online-features"))
                    .setStartingOffsets(OffsetsInitializer.earliest())
                    .setValueOnlyDeserializer(new SimpleStringSchema())
                    .setProperties(kafkaProperties(params))
                    .build();

            return env.fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-movie-events")
                    .flatMap((String line, Collector<MovieEvent> out) -> {
                        MovieEvent e = parseEvent(line);
                        if (e != null) out.collect(e);
                    }).returns(MovieEvent.class);
        }

        String inputFile = params.get("input-file", "streaming/online-serving/data/movie_events.ndjson");
        return env.readTextFile(inputFile)
                .filter(line -> !line.isBlank())
                .flatMap((String line, Collector<MovieEvent> out) -> {
                    MovieEvent e = parseEvent(line);
                    if (e != null) out.collect(e);
                }).returns(MovieEvent.class);
    }

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
            recentMoviesState = getRuntimeContext().getListState(
                    new ListStateDescriptor<>("recent-movie-ids", Types.INT));
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
                    ttlSeconds
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
            state = getRuntimeContext().getState(
                    new ValueStateDescriptor<>("user-embedding-feature", UserEmbeddingState.class));
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
                    ttlSeconds
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
            state = getRuntimeContext().getState(
                    new ValueStateDescriptor<>("session-feature", SessionFeatureState.class));
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
                    ttlSeconds
            ));
        }
    }

    static final class DeduplicateEventsFunction extends KeyedProcessFunction<String, MovieEvent, MovieEvent> {
        private final long ttlSeconds;
        private transient ValueState<Boolean> seen;

        DeduplicateEventsFunction(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        @Override
        public void open(Configuration parameters) {
            ValueStateDescriptor<Boolean> descriptor = new ValueStateDescriptor<>("seen-event-id", Types.BOOLEAN);
            StateTtlConfig ttlConfig = StateTtlConfig
                    .newBuilder(org.apache.flink.api.common.time.Time.seconds(Math.max(1L, ttlSeconds)))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                    .cleanupIncrementally(100, true)
                    .build();
            descriptor.enableTimeToLive(ttlConfig);
            seen = getRuntimeContext().getState(descriptor);
        }

        @Override
        public void processElement(MovieEvent event,
                                   KeyedProcessFunction<String, MovieEvent, MovieEvent>.Context context,
                                   Collector<MovieEvent> out) throws Exception {
            if (Boolean.TRUE.equals(seen.value())) {
                LOG.debug("Skipping duplicate movie event: {}", event.eventId);
                return;
            }
            seen.update(true);
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

    static final class TopKAllWindowFunction implements AllWindowFunction<MovieEvent, TopKSnapshot, TimeWindow> {
        private final int topK;
        private final String windowLabel;
        private final int ttlSeconds;

        TopKAllWindowFunction(int topK, String windowLabel, int ttlSeconds) {
            this.topK = topK;
            this.windowLabel = windowLabel;
            this.ttlSeconds = ttlSeconds;
        }

        @Override
        public void apply(TimeWindow window, Iterable<MovieEvent> values, Collector<TopKSnapshot> out) {
            Map<Integer, Long> scores = new HashMap<>();
            for (MovieEvent event : values) {
                scores.merge(event.movieId, event.engagementWeight(), Long::sum);
            }

            List<ScoredMovie> ranked = scores.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry::getKey))
                    .limit(topK)
                    .map(entry -> new ScoredMovie(entry.getKey(), entry.getValue()))
                    .toList();

            out.collect(new TopKSnapshot("topk:" + windowLabel, ranked, window.getEnd(), ttlSeconds));
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
        transient JedisPool pool;

        AbstractRedisSink(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public void open(Configuration parameters) {
            pool = new JedisPool(host, port);
        }

        @Override
        public void close() {
            if (pool != null) pool.close();
        }

        void setStringIfNewer(Jedis jedis, String redisKey, String value, long updatedAtMillis, int ttlSeconds) {
            jedis.eval(
                    SET_IF_NEWER_SCRIPT,
                    List.of(redisKey, redisKey + ":updated_at"),
                    List.of(Long.toString(updatedAtMillis), Integer.toString(ttlSeconds), value)
            );
        }

        void setTopKIfNewer(Jedis jedis, TopKSnapshot value) {
            List<String> args = new ArrayList<>(2 + value.movies.size() * 2);
            args.add(Long.toString(value.updatedAtMillis));
            args.add(Integer.toString(value.ttlSeconds));
            for (ScoredMovie movie : value.movies) {
                args.add(Integer.toString(movie.movieId));
                args.add(Long.toString(movie.score));
            }
            jedis.eval(
                    ZSET_IF_NEWER_SCRIPT,
                    List.of(value.redisKey, value.redisKey + ":updated_at"),
                    args
            );
        }
    }

    static final class RedisStringFeatureSink extends AbstractRedisSink<StringFeatureUpdate> {
        RedisStringFeatureSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(StringFeatureUpdate value, Context context) {
            try (Jedis jedis = pool.getResource()) {
                setStringIfNewer(jedis, value.redisKey, value.value, value.updatedAtMillis, value.ttlSeconds);
            }
        }
    }

    static final class RedisRecentMoviesSink extends AbstractRedisSink<UserRecentMoviesUpdate> {
        RedisRecentMoviesSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(UserRecentMoviesUpdate value, Context context) {
            try (Jedis jedis = pool.getResource()) {
                setStringIfNewer(jedis, value.redisKey, value.value, value.updatedAtMillis, value.ttlSeconds);
            }
        }
    }

    static final class RedisMovieMetricSink extends AbstractRedisSink<MovieMetricUpdate> {
        RedisMovieMetricSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(MovieMetricUpdate value, Context context) {
            try (Jedis jedis = pool.getResource()) {
                setStringIfNewer(jedis, value.redisKey, Long.toString(value.count),
                        value.updatedAtMillis, value.ttlSeconds);
            }
        }
    }

    static final class RedisTopKSink extends AbstractRedisSink<TopKSnapshot> {
        RedisTopKSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(TopKSnapshot value, Context context) {
            try (Jedis jedis = pool.getResource()) {
                setTopKIfNewer(jedis, value);
                setTopKIfNewer(jedis, value.withRedisKey(value.redisKey.replace("topk:", "feature:hot_movies:")));
            }
        }
    }

    static final class RedisTrendFeatureSink extends AbstractRedisSink<TopKSnapshot> {
        RedisTrendFeatureSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(TopKSnapshot value, Context context) {
            try (Jedis jedis = pool.getResource()) {
                setStringIfNewer(jedis, value.redisKey.replace("topk:", "feature:trend:"),
                        value.encodeTrend(), value.updatedAtMillis, value.ttlSeconds);
            }
        }
    }

    static final class UserRecentMoviesUpdate {
        final String redisKey;
        final String value;
        final long updatedAtMillis;
        final int ttlSeconds;

        UserRecentMoviesUpdate(String redisKey, String value, long updatedAtMillis, int ttlSeconds) {
            this.redisKey = redisKey;
            this.value = value;
            this.updatedAtMillis = updatedAtMillis;
            this.ttlSeconds = ttlSeconds;
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

        StringFeatureUpdate(String redisKey, String value, long updatedAtMillis, int ttlSeconds) {
            this.redisKey = redisKey;
            this.value = value;
            this.updatedAtMillis = updatedAtMillis;
            this.ttlSeconds = ttlSeconds;
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

    static final class ScoredMovie {
        final int movieId;
        final long score;

        ScoredMovie(int movieId, long score) {
            this.movieId = movieId;
            this.score = score;
        }
    }

    static final class TopKSnapshot {
        final String redisKey;
        final List<ScoredMovie> movies;
        final long updatedAtMillis;
        final int ttlSeconds;

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
