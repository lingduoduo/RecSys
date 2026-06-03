package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedRecordStoreWriteTest extends RedisShardingTestBase {

    private ShardedRecordStore store;
    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring  = new ConsistentHashRing(2, 150);
        store = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void write_returnsOkWithCorrectShardIndex() {
        ShardedRecord record = new ShardedRecord("device-1", 0, RecordType.EVENT,
                "evt-001", "{\"action\":\"click\"}", System.currentTimeMillis());

        WriteResult result = store.write(record);

        assertThat(result.status()).isEqualTo(WriteStatus.OK);
        assertThat(result.seqNum()).isGreaterThan(0);
        assertThat(result.shardIndex()).isEqualTo(ring.shardFor("device-1"));
    }

    @Test
    void write_storesFullRecordInHashKey() {
        ShardedRecord record = new ShardedRecord("device-2", 0, RecordType.FEATURE,
                "feat-001", "{\"ctr\":0.12}", System.currentTimeMillis());

        WriteResult result = store.write(record);

        try (Jedis jedis = pool.getResource()) {
            Map<String, String> hash = jedis.hgetAll(
                    "sr:rec:" + result.shardIndex() + ":" + result.seqNum());
            assertThat(hash)
                    .containsEntry("deviceId", "device-2")
                    .containsEntry("type", "FEATURE")
                    .containsEntry("eventId", "feat-001")
                    .containsEntry("payload", "{\"ctr\":0.12}");
        }
    }

    @Test
    void write_addsMemberToDeviceSortedSet() {
        ShardedRecord record = new ShardedRecord("device-3", 0, RecordType.EVENT,
                "evt-002", "{}", System.currentTimeMillis());

        WriteResult result = store.write(record);

        try (Jedis jedis = pool.getResource()) {
            Double score = jedis.zscore(
                    "sr:dev:" + result.shardIndex() + ":device-3", "evt-002");
            assertThat(score).isEqualTo((double) result.seqNum());
        }
    }

    @Test
    void write_appendsEntryToShardStream() {
        ShardedRecord record = new ShardedRecord("device-4", 0, RecordType.LOG,
                "log-001", "startup", System.currentTimeMillis());

        WriteResult result = store.write(record);

        try (Jedis jedis = pool.getResource()) {
            long streamLen = jedis.xlen("sr:stream:" + result.shardIndex());
            assertThat(streamLen).isGreaterThan(0);
        }
    }

    @Test
    void write_duplicateEventIdReturnsDuplicate() {
        ShardedRecord r1 = new ShardedRecord("device-5", 0, RecordType.EVENT,
                "evt-dup", "{}", System.currentTimeMillis());
        ShardedRecord r2 = new ShardedRecord("device-5", 0, RecordType.EVENT,
                "evt-dup", "{\"retry\":true}", System.currentTimeMillis());

        WriteResult first  = store.write(r1);
        WriteResult second = store.write(r2);

        assertThat(first.status()).isEqualTo(WriteStatus.OK);
        assertThat(second.status()).isEqualTo(WriteStatus.DUPLICATE);
    }

    @Test
    void update_staleSeqDoesNotOverwriteNewerRecord() {
        ShardedRecord r1 = new ShardedRecord("device-6", 0, RecordType.FEATURE,
                "feat-ver", "{\"v\":1}", System.currentTimeMillis());
        WriteResult first = store.write(r1);

        // Force a high score for same eventId.
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd("sr:dev:" + first.shardIndex() + ":device-6",
                    9999.0, "feat-ver");
        }

        ShardedRecord r2 = new ShardedRecord("device-6", 0, RecordType.FEATURE,
                "feat-ver", "{\"v\":2}", System.currentTimeMillis());
        store.update(r2);

        try (Jedis jedis = pool.getResource()) {
            Double score = jedis.zscore(
                    "sr:dev:" + first.shardIndex() + ":device-6", "feat-ver");
            assertThat(score).isEqualTo(9999.0);
        }
    }
}
