package com.recsys.online.flink;

import com.recsys.infrastructure.vectordb.VectorMath;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.apache.flink.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OnlineFeatureStreamingJobTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /** Connects a short-lived Lettuce client for assertions, then shuts it down. */
    private static void withRedis(String host, int port, Consumer<RedisCommands<String, String>> body) {
        RedisClient client = RedisClient.create(RedisURI.create(host, port));
        try (StatefulRedisConnection<String, String> conn = client.connect(StringCodec.UTF8)) {
            body.accept(conn.sync());
        } finally {
            client.shutdown();
        }
    }

    // ── pre-existing tests (unchanged) ──────────────────────────────────────

    @Test
    void encodeVectorUsesSpaceSeparator() throws Exception {
        var method = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeVector", double[].class);
        method.setAccessible(true);
        var fn = new OnlineFeatureStreamingJob.UserEmbeddingFunction(4, 3600);
        String encoded = (String) method.invoke(fn, new double[]{1.0, 0.0, 0.0, 0.0});
        assertThat(encoded).doesNotContain(",");
        assertThat(encoded).contains(" ");
    }

    @Test
    void encodedVectorIsParsableByVectorMath() throws Exception {
        var method = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeVector", double[].class);
        method.setAccessible(true);
        var fn = new OnlineFeatureStreamingJob.UserEmbeddingFunction(4, 3600);
        String encoded = (String) method.invoke(fn, new double[]{3.0, 4.0, 0.0, 0.0});
        float[] parsed = VectorMath.parseVector(encoded);
        assertThat(parsed).hasSize(4);
        assertThat(parsed[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(parsed[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    void accumulatesRawCountsNotNormalisedValues() throws Exception {
        var rawMethod = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeRaw", double[].class);
        rawMethod.setAccessible(true);
        var encodeMethod = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeVector", double[].class);
        encodeMethod.setAccessible(true);
        var fn = new OnlineFeatureStreamingJob.UserEmbeddingFunction(4, 3600);
        double[] rawAfterFirst = {2.0, 0.0, 0.0, 0.0};
        String rawStored = (String) rawMethod.invoke(fn, rawAfterFirst);
        var parseMethod = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("parseVector", String.class, int.class);
        parseMethod.setAccessible(true);
        double[] restored = (double[]) parseMethod.invoke(fn, rawStored, 4);
        restored[1] += 2.0;
        assertThat(restored[0]).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(restored[1]).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.001));
        String redisOutput = (String) encodeMethod.invoke(fn, restored);
        float[] parsed = VectorMath.parseVector(redisOutput);
        assertThat(parsed[0]).isCloseTo(0.707f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(parsed[1]).isCloseTo(0.707f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void stringFeatureUpdateCarriesEventId() throws Exception {
        var ctor = OnlineFeatureStreamingJob.StringFeatureUpdate.class
                .getDeclaredConstructor(String.class, String.class, long.class, int.class, String.class);
        ctor.setAccessible(true);
        var update = ctor.newInstance("u2vEmb:1", "0.5 0.5", 1000L, 3600, "evt-test");
        var field = OnlineFeatureStreamingJob.StringFeatureUpdate.class.getDeclaredField("eventId");
        field.setAccessible(true);
        assertThat(field.get(update)).isEqualTo("evt-test");
    }

    // ── Task 2 tests (require Docker) ────────────────────────────────────────

    @Test
    void luaScriptWritesCompanionKeys() throws Exception {
        String host = REDIS.getHost();
        int port = REDIS.getMappedPort(6379);

        var sink = new OnlineFeatureStreamingJob.RedisStringFeatureSink(host, port);
        sink.open(new Configuration());
        try {
            var update = new OnlineFeatureStreamingJob.StringFeatureUpdate(
                    "u2vEmb:10", "0.5 0.5", 1000L, 3600, "evt-abc");
            sink.invoke(update, null);

            withRedis(host, port, cmd -> {
                assertThat(cmd.get("u2vEmb:10")).isEqualTo("0.5 0.5");
                assertThat(cmd.get("u2vEmb:10:last_event")).isEqualTo("evt-abc");
                assertThat(cmd.lrange("u2vEmb:10:event_history", 0, -1))
                        .containsExactly("evt-abc");
                assertThat(cmd.smembers("lineage:event:evt-abc"))
                        .containsExactly("u2vEmb:10");
            });
        } finally {
            sink.close();
        }
    }

    @Test
    void luaScriptSkipsLineageWhenNewerExists() throws Exception {
        String host = REDIS.getHost();
        int port = REDIS.getMappedPort(6379);

        var sink = new OnlineFeatureStreamingJob.RedisStringFeatureSink(host, port);
        sink.open(new Configuration());
        try {
            sink.invoke(new OnlineFeatureStreamingJob.StringFeatureUpdate(
                    "u2vEmb:20", "0.6 0.8", 2000L, 3600, "evt-first"), null);

            sink.invoke(new OnlineFeatureStreamingJob.StringFeatureUpdate(
                    "u2vEmb:20", "0.1 0.2", 1000L, 3600, "evt-stale"), null);

            withRedis(host, port, cmd -> {
                assertThat(cmd.get("u2vEmb:20")).isEqualTo("0.6 0.8");
                assertThat(cmd.get("u2vEmb:20:last_event")).isEqualTo("evt-first");
                assertThat(cmd.lrange("u2vEmb:20:event_history", 0, -1))
                        .containsExactly("evt-first");
                assertThat(cmd.smembers("lineage:event:evt-stale")).isEmpty();
            });
        } finally {
            sink.close();
        }
    }

    @Test
    void eventHistoryCapAtFive() throws Exception {
        String host = REDIS.getHost();
        int port = REDIS.getMappedPort(6379);

        var sink = new OnlineFeatureStreamingJob.RedisStringFeatureSink(host, port);
        sink.open(new Configuration());
        try {
            for (int i = 1; i <= 6; i++) {
                sink.invoke(new OnlineFeatureStreamingJob.StringFeatureUpdate(
                        "u2vEmb:30", "0.5 0.5", (long) i * 1000, 3600,
                        "evt-" + String.format("%03d", i)), null);
            }

            withRedis(host, port, cmd -> {
                var history = cmd.lrange("u2vEmb:30:event_history", 0, -1);
                assertThat(history).hasSize(5);
                assertThat(history.get(0)).isEqualTo("evt-002");
                assertThat(history.get(4)).isEqualTo("evt-006");
            });
        } finally {
            sink.close();
        }
    }
}
