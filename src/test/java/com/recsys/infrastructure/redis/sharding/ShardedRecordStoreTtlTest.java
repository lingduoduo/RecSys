package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
class ShardedRecordStoreTtlTest extends RedisShardingTestBase {

    private ShardedRecordStore store;

    @BeforeEach
    void setUp() {
        var ring = new ConsistentHashRing(1, 150);
        store = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void write_withTtl_setsExpireOnHashKey() {
        ShardedRecord record = new ShardedRecord("dev-ttl", 0, RecordType.LOG,
                "log-ttl", "data", System.currentTimeMillis());

        WriteResult result = store.write(record, 3600);

        try (Jedis jedis = pool.getResource()) {
            long ttl = jedis.ttl("sr:rec:0:" + result.seqNum());
            assertThat(ttl).isBetween(3598L, 3600L);
        }
    }

    @Test
    void write_withTtl_zsetMemberSurvivesExpiry() throws InterruptedException {
        ShardedRecord record = new ShardedRecord("dev-ttl2", 0, RecordType.EVENT,
                "evt-short", "data", System.currentTimeMillis());

        WriteResult result = store.write(record, 1); // 1 second TTL

        Thread.sleep(1100); // wait for hash to expire

        try (Jedis jedis = pool.getResource()) {
            // Hash key expired
            assertThat(jedis.exists("sr:rec:0:" + result.seqNum())).isFalse();
            // ZSet member still present (no TTL set on ZSet)
            Double score = jedis.zscore("sr:dev:0:dev-ttl2", "evt-short");
            assertThat(score).isNotNull();
        }
    }

    @Test
    void readDevice_hasMore_notAffectedByExpiredRecords() throws InterruptedException {
        // Write 3 records: first two expire quickly, third does not.
        // With limit=2, Redis ZSet returns 2 entries (a full page), so hasMore must be true
        // even though the 2 HGETALL results are empty after expiry.
        for (int i = 1; i <= 3; i++) {
            ShardedRecord r = new ShardedRecord("dev-hm", 0, RecordType.EVENT,
                    "e" + i, "v" + i, System.currentTimeMillis());
            store.write(r, i <= 2 ? 1 : 0);
        }

        Thread.sleep(1100);

        Page<ShardedRecord> page1 = store.readDevice("dev-hm", ShardCursor.start(), 2);
        assertThat(page1.hasMore()).isTrue(); // ZSet had 2 entries → full page → more may exist
        assertThat(page1.records()).isEmpty(); // both expired

        Page<ShardedRecord> page2 = store.readDevice("dev-hm", page1.next(), 2);
        assertThat(page2.records()).hasSize(1);
        assertThat(page2.records().get(0).eventId()).isEqualTo("e3");
        assertThat(page2.hasMore()).isFalse();
    }

    @Test
    void readDevice_skipsExpiredRecords() throws InterruptedException {
        ShardedRecord r1 = new ShardedRecord("dev-ttl3", 0, RecordType.EVENT,
                "short", "v1", System.currentTimeMillis());
        ShardedRecord r2 = new ShardedRecord("dev-ttl3", 0, RecordType.EVENT,
                "long", "v2", System.currentTimeMillis());

        store.write(r1, 1);  // expires in 1s
        store.write(r2, 0);  // no expiry

        Thread.sleep(1100);

        Page<ShardedRecord> page = store.readDevice("dev-ttl3", ShardCursor.start(), 10);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).eventId()).isEqualTo("long");
    }
}
