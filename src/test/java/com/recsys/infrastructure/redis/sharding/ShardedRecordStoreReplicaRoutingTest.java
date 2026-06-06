package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that ShardedRecordStore routes writes to writePool and reads to
 * readPool when they are distinct — proving AZ-aware replica routing works.
 *
 * Setup: two isolated Redis containers representing a primary (AZ-a) and a
 * read replica (AZ-b).  The read replica starts empty and is NOT populated by
 * any replication in this test, so reads on an empty replica confirm routing
 * rather than Redis replication behaviour.
 */
@Tag("docker")
@Testcontainers
class ShardedRecordStoreReplicaRoutingTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> PRIMARY =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> REPLICA =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private JedisPool writePool;
    private JedisPool readPool;
    private ShardedRecordStore store;

    @BeforeEach
    void setUp() {
        writePool = new JedisPool(PRIMARY.getHost(), PRIMARY.getMappedPort(6379));
        readPool  = new JedisPool(REPLICA.getHost(), REPLICA.getMappedPort(6379));

        ConsistentHashRing ring = new ConsistentHashRing(1, 150);
        SequenceGenerator  seqGen = new SequenceGenerator(writePool, "rr:");
        store = new ShardedRecordStore(writePool, readPool, ring, seqGen, "rr:");
    }

    @AfterEach
    void tearDown() {
        try (Jedis p = writePool.getResource()) { p.flushAll(); }
        try (Jedis r = readPool.getResource())  { r.flushAll(); }
        writePool.close();
        readPool.close();
    }

    @Test
    void write_landsOnWritePool_notOnReadPool() {
        ShardedRecord rec = new ShardedRecord("dev-rr", 0, RecordType.EVENT,
                "evt-1", "{}", System.currentTimeMillis());
        store.write(rec);

        // The record hash key must exist in PRIMARY (writePool).
        try (Jedis primary = writePool.getResource()) {
            long primaryKeys = primary.dbSize();
            assertThat(primaryKeys).isGreaterThan(0);
        }

        // REPLICA is untouched — store did not write there.
        try (Jedis replica = readPool.getResource()) {
            assertThat(replica.dbSize()).isZero();
        }
    }

    @Test
    void readDevice_goesToReadPool_emptyWhenReplicaHasNoData() {
        // Write a record so PRIMARY has data.
        ShardedRecord rec = new ShardedRecord("dev-rr2", 0, RecordType.EVENT,
                "evt-2", "{}", System.currentTimeMillis());
        store.write(rec);

        // readDevice reads from readPool (REPLICA), which is empty → no results.
        Page<ShardedRecord> page = store.readDevice("dev-rr2", ShardCursor.start(), 10);
        assertThat(page.records()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void readDevice_returnsDataWhenReplicaIsPopulated() {
        // Simulate replication by writing directly to both pools.
        ShardedRecord rec = new ShardedRecord("dev-rr3", 0, RecordType.EVENT,
                "evt-3", "{}", System.currentTimeMillis());
        store.write(rec);

        // Copy data to replica to simulate async replication catching up.
        try (Jedis primary = writePool.getResource();
             Jedis replica = readPool.getResource()) {
            primary.keys("rr:*").forEach(key -> {
                String type = primary.type(key);
                if ("hash".equals(type)) {
                    replica.hset(key, primary.hgetAll(key));
                } else if ("zset".equals(type)) {
                    primary.zrangeWithScores(key, 0, -1)
                           .forEach(t -> replica.zadd(key, t.getScore(), t.getElement()));
                }
            });
        }

        Page<ShardedRecord> page = store.readDevice("dev-rr3", ShardCursor.start(), 10);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).eventId()).isEqualTo("evt-3");
    }

    @Test
    void readShard_goesToReadPool_emptyWhenReplicaLacksStream() {
        ShardedRecord rec = new ShardedRecord("dev-rr4", 0, RecordType.LOG,
                "log-1", "data", System.currentTimeMillis());
        WriteResult result = store.write(rec);

        // Stream was written to PRIMARY only; readShard reads from REPLICA (empty stream).
        Page<ShardedRecord> page = store.readShard(result.shardIndex(), ShardCursor.start(), 10);
        assertThat(page.records()).isEmpty();
    }
}
