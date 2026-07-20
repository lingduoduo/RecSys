package com.recsys.infrastructure.store;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.redis.LettuceRedisExecutor;
import com.recsys.infrastructure.redis.RedisExecutor;
import com.recsys.infrastructure.redis.sharding.ConsistentHashRing;
import com.recsys.infrastructure.redis.sharding.SequenceGenerator;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.api.online.AdminTokenGuard;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@Testcontainers
class ShardedRecordServiceIntegrationTest {

    static final String ADMIN_TOKEN = "ops-token";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // Initialized in configure() — Testcontainers starts @Container before @RegisterExtension.
    static RedisExecutor exec;

    @AfterEach
    void flushRedis() {
        exec.execute(c -> { c.flushall(); return null; });
    }

    @AfterAll
    static void closePool() {
        if (exec != null) exec.close();
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            RedisClient client = RedisClient.create(
                    RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
            exec = new LettuceRedisExecutor(client, new GenericObjectPoolConfig<>(), true);
            ShardedRecordStore store = new ShardedRecordStore(
                    exec,
                    new ConsistentHashRing(2, 150),
                    new SequenceGenerator(exec, "sr:"),
                    "sr:");
            sb.service(Route.builder().pathPrefix("/shards/").build(),
                    new ShardedRecordService(store, null, ADMIN_TOKEN, 0L,
                            System::currentTimeMillis));
        }
    };

    // ── POST /shards/records ─────────────────────────────────────────────────────

    @Test
    void postRecord_returns200WithSeqAndShard() {
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/shards/records",
                "{\"deviceId\":\"dev-1\",\"type\":\"EVENT\",\"eventId\":\"e1\",\"payload\":\"{}\"}");

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8())
                .contains("seqNum")
                .contains("shardIndex")
                .contains("OK");
    }

    @Test
    void postRecord_missingDeviceId_returns400() {
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/shards/records",
                "{\"type\":\"EVENT\",\"eventId\":\"e1\"}");

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.contentUtf8()).contains("deviceId");
    }

    @Test
    void postRecord_missingEventId_returns400() {
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/shards/records",
                "{\"deviceId\":\"dev-1\",\"type\":\"EVENT\"}");

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.contentUtf8()).contains("eventId");
    }

    @Test
    void postRecord_invalidType_returns400() {
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/shards/records",
                "{\"deviceId\":\"dev-1\",\"type\":\"UNKNOWN\",\"eventId\":\"e1\"}");

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.contentUtf8()).contains("invalid type");
    }

    @Test
    void postRecord_defaultsToEventTypeWhenOmitted() {
        AggregatedHttpResponse r = server.blockingWebClient().post(
                "/shards/records",
                "{\"deviceId\":\"dev-1\",\"eventId\":\"e2\"}");

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("OK");
    }

    @Test
    void postRecord_featureAndLogTypesAccepted() {
        AggregatedHttpResponse r1 = server.blockingWebClient().post(
                "/shards/records",
                "{\"deviceId\":\"dev-2\",\"type\":\"FEATURE\",\"eventId\":\"f1\",\"payload\":\"{\\\"engagement\\\":0.5}\"}");
        AggregatedHttpResponse r2 = server.blockingWebClient().post(
                "/shards/records",
                "{\"deviceId\":\"dev-2\",\"type\":\"LOG\",\"eventId\":\"l1\",\"payload\":\"startup\"}");

        assertThat(r1.status()).isEqualTo(HttpStatus.OK);
        assertThat(r2.status()).isEqualTo(HttpStatus.OK);
    }

    // ── GET /shards/device ───────────────────────────────────────────────────────

    @Test
    void getDevice_returnsWrittenRecords() {
        server.blockingWebClient().post("/shards/records",
                "{\"deviceId\":\"dev-A\",\"type\":\"EVENT\",\"eventId\":\"a1\",\"payload\":\"{}\"}");
        server.blockingWebClient().post("/shards/records",
                "{\"deviceId\":\"dev-A\",\"type\":\"EVENT\",\"eventId\":\"a2\",\"payload\":\"{}\"}");

        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/shards/device?deviceId=dev-A&limit=10");

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8())
                .contains("dev-A")
                .contains("a1")
                .contains("a2")
                .contains("\"count\":2");
    }

    @Test
    void getDevice_missingDeviceId_returns400() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/shards/device");
        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.contentUtf8()).contains("deviceId");
    }

    @Test
    void getDevice_unknownDevice_returnsEmptyPage() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/shards/device?deviceId=ghost");

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8())
                .contains("\"count\":0")
                .contains("\"hasMore\":false");
    }

    @Test
    void getDevice_paginationCursorAdvances() {
        for (int i = 1; i <= 5; i++) {
            server.blockingWebClient().post("/shards/records",
                    "{\"deviceId\":\"dev-B\",\"type\":\"EVENT\",\"eventId\":\"b" + i + "\",\"payload\":\"{}\"}");
        }

        AggregatedHttpResponse page1 = server.blockingWebClient()
                .get("/shards/device?deviceId=dev-B&limit=2");
        assertThat(page1.status()).isEqualTo(HttpStatus.OK);
        assertThat(page1.contentUtf8()).contains("\"hasMore\":true");

        String cursor = page1.contentUtf8().replaceAll(".*\"cursor\":\"([^\"]+)\".*", "$1");
        AggregatedHttpResponse page2 = server.blockingWebClient()
                .get("/shards/device?deviceId=dev-B&limit=2&cursor=" + cursor);
        assertThat(page2.status()).isEqualTo(HttpStatus.OK);
        assertThat(page2.contentUtf8()).contains("\"hasMore\":true");
    }

    @Test
    void getDevice_onlyReturnsOwnDevice_notOthers() {
        server.blockingWebClient().post("/shards/records",
                "{\"deviceId\":\"dev-X\",\"type\":\"EVENT\",\"eventId\":\"x1\",\"payload\":\"{}\"}");
        server.blockingWebClient().post("/shards/records",
                "{\"deviceId\":\"dev-Y\",\"type\":\"EVENT\",\"eventId\":\"y1\",\"payload\":\"{}\"}");

        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/shards/device?deviceId=dev-X&limit=10");

        assertThat(r.contentUtf8()).contains("x1").doesNotContain("y1");
    }

    // ── GET /shards/shard ────────────────────────────────────────────────────────

    @Test
    void getShard_returnsRecordsAcrossDevices() {
        server.blockingWebClient().post("/shards/records",
                "{\"deviceId\":\"dev-P\",\"type\":\"EVENT\",\"eventId\":\"p1\",\"payload\":\"{}\"}");
        server.blockingWebClient().post("/shards/records",
                "{\"deviceId\":\"dev-Q\",\"type\":\"EVENT\",\"eventId\":\"q1\",\"payload\":\"{}\"}");

        // Records may land on shard 0 or 1; read both and verify at least one is non-empty.
        AggregatedHttpResponse s0 = getShard(0);
        AggregatedHttpResponse s1 = getShard(1);

        assertThat(s0.status()).isEqualTo(HttpStatus.OK);
        assertThat(s1.status()).isEqualTo(HttpStatus.OK);

        int total = parseCount(s0.contentUtf8()) + parseCount(s1.contentUtf8());
        assertThat(total).isEqualTo(2);
    }

    @Test
    void getShard_emptyShard_returnsEmptyPage() {
        AggregatedHttpResponse r = getShard(0);
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8())
                .contains("\"count\":0")
                .contains("\"hasMore\":false");
    }

    @Test
    void getShard_withoutAdminToken_returns403() {
        // Bulk shard dump is operator-gated; a request without the token is rejected.
        AggregatedHttpResponse r = server.blockingWebClient().get("/shards/shard?index=0&limit=5");
        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.contentUtf8()).contains("operator token required");
    }

    /** GET /shards/shard with the operator token (bulk read is admin-gated). */
    private static AggregatedHttpResponse getShard(int index) {
        return server.blockingWebClient()
                .prepare().get("/shards/shard?index=" + index + "&limit=10")
                .header(AdminTokenGuard.HEADER, ADMIN_TOKEN).execute();
    }

    private static int parseCount(String json) {
        int idx = json.indexOf("\"count\":");
        if (idx < 0) return 0;
        int start = idx + 8;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == ' ')) end++;
        return Integer.parseInt(json.substring(start, end).trim());
    }
}
