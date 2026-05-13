package com.recsys.streaming.flink;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
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

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(Math.max(5_000L, windowSeconds * 1_000L));

        DataStream<MovieEvent> events = buildEventStream(env, params)
                .filter(e -> e != null)
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
                .filter(event -> metricKind(event) != null)
                .keyBy(event -> event.movieId + "|" + metricKind(event))
                .window(TumblingProcessingTimeWindows.of(Time.seconds(windowSeconds)))
                .aggregate(new CountAggregate(), new MovieMetricWindowFunction(windowLabel, metricTtlSeconds))
                .name("movie-metrics")
                .addSink(new RedisMovieMetricSink(redisHost, redisPort))
                .name("redis-movie-metric-sink");

        events
                .filter(event -> event.engagementWeight() > 0L)
                .windowAll(TumblingProcessingTimeWindows.of(Time.seconds(windowSeconds)))
                .apply(new TopKAllWindowFunction(topK, windowLabel, metricTtlSeconds))
                .name("topk-window")
                .addSink(new RedisTopKSink(redisHost, redisPort))
                .name("redis-topk-sink");

        env.execute("recsys-online-feature-streaming");
    }

    private static DataStream<MovieEvent> buildEventStream(StreamExecutionEnvironment env,
                                                           ParameterTool params) throws IOException {
        String bootstrapServers = params.get("bootstrap.servers");
        String topic = params.get("topic", "movie_events");

        if (!StringUtils.isNullOrWhitespaceOnly(bootstrapServers)) {
            KafkaSource<String> source = KafkaSource.<String>builder()
                    .setBootstrapServers(bootstrapServers)
                    .setTopics(topic)
                    .setGroupId(params.get("group.id", "recsys-online-feature-job"))
                    .setStartingOffsets(OffsetsInitializer.earliest())
                    .setValueOnlyDeserializer(new SimpleStringSchema())
                    .setProperties(kafkaProperties(params))
                    .build();

            return env.fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-movie-events")
                    .map(OnlineFeatureStreamingJob::parseEvent);
        }

        String inputFile = params.get("input-file", "streaming/online-serving/data/movie_events.ndjson");
        return env.readTextFile(inputFile)
                .filter(line -> !line.isBlank())
                .map(OnlineFeatureStreamingJob::parseEvent);
    }

    private static Properties kafkaProperties(ParameterTool params) {
        Properties properties = new Properties();
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
        if (event.isImpression()) {
            return "impressions_1h";
        }
        if (event.isOrder()) {
            return "orders_1h";
        }
        if (event.isLike()) {
            return "likes_1h";
        }
        if (event.isClick()) {
            return "clicks_1h";
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
    }

    static final class RedisRecentMoviesSink extends AbstractRedisSink<UserRecentMoviesUpdate> {
        RedisRecentMoviesSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(UserRecentMoviesUpdate value, Context context) {
            try (Jedis jedis = pool.getResource()) {
                jedis.setex(value.redisKey, value.ttlSeconds, value.value);
            }
        }
    }

    static final class RedisMovieMetricSink extends AbstractRedisSink<MovieMetricUpdate> {
        RedisMovieMetricSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(MovieMetricUpdate value, Context context) {
            try (Jedis jedis = pool.getResource()) {
                jedis.setex(value.redisKey, value.ttlSeconds, Long.toString(value.count));
            }
        }
    }

    static final class RedisTopKSink extends AbstractRedisSink<TopKSnapshot> {
        RedisTopKSink(String host, int port) { super(host, port); }

        @Override
        public void invoke(TopKSnapshot value, Context context) {
            try (Jedis jedis = pool.getResource()) {
                var pipe = jedis.pipelined();
                pipe.del(value.redisKey);
                for (ScoredMovie movie : value.movies) {
                    pipe.zadd(value.redisKey, movie.score, Integer.toString(movie.movieId));
                }
                pipe.expire(value.redisKey, (long) value.ttlSeconds);
                pipe.sync();
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
    }
}
