package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import static org.assertj.core.api.Assertions.assertThat;

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
