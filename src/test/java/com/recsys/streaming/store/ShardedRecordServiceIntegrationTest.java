package com.recsys.streaming.store;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.infrastructure.redis.sharding.ConsistentHashRing;
import com.recsys.infrastructure.redis.sharding.SequenceGenerator;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.Pool;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@Testcontainers
class ShardedRecordServiceIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // Initialized in configure() — Testcontainers starts @Container before @RegisterExtension.
    static Pool<Jedis> pool;

    @AfterEach
    void flushRedis() {
        try (Jedis jedis = pool.getResource()) { jedis.flushAll(); }
    }

    @AfterAll
    static void closePool() {
        if (pool != null) pool.close();
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
            ShardedRecordStore store = new ShardedRecordStore(
                    pool,
                    new ConsistentHashRing(2, 150),
                    new SequenceGenerator(pool, "sr:"),
                    "sr:");
            sb.service(Route.builder().pathPrefix("/shards/").build(),
                    new ShardedRecordService(store));
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
                "{\"deviceId\":\"dev-2\",\"type\":\"FEATURE\",\"eventId\":\"f1\",\"payload\":\"{\\\"ctr\\\":0.5}\"}");
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
        AggregatedHttpResponse s0 = server.blockingWebClient().get("/shards/shard?index=0&limit=10");
        AggregatedHttpResponse s1 = server.blockingWebClient().get("/shards/shard?index=1&limit=10");

        assertThat(s0.status()).isEqualTo(HttpStatus.OK);
        assertThat(s1.status()).isEqualTo(HttpStatus.OK);

        int total = parseCount(s0.contentUtf8()) + parseCount(s1.contentUtf8());
        assertThat(total).isEqualTo(2);
    }

    @Test
    void getShard_emptyShard_returnsEmptyPage() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/shards/shard?index=0&limit=5");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8())
                .contains("\"count\":0")
                .contains("\"hasMore\":false");
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
