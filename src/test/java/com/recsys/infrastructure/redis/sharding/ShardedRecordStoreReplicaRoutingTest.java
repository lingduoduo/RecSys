package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.LettuceRedisExecutor;
import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that ShardedRecordStore routes writes to writeExec and reads to
 * readExec when they are distinct — proving AZ-aware replica routing works.
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

    private RedisExecutor writeExec;
    private RedisExecutor readExec;
    private ShardedRecordStore store;

    private static RedisExecutor newExecutor(GenericContainer<?> container) {
        RedisClient client = RedisClient.create(
                RedisURI.create(container.getHost(), container.getMappedPort(6379)));
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> cfg =
                new GenericObjectPoolConfig<>();
        return new LettuceRedisExecutor(client, cfg, true);
    }

    private static RedisCommands<String, String> cmd(RedisExecutor exec) {
        return exec.execute(c -> c);
    }

    @BeforeEach
    void setUp() {
        writeExec = newExecutor(PRIMARY);
        readExec  = newExecutor(REPLICA);

        ConsistentHashRing ring = new ConsistentHashRing(1, 150);
        SequenceGenerator  seqGen = new SequenceGenerator(writeExec, "rr:");
        store = new ShardedRecordStore(writeExec, readExec, ring, seqGen, "rr:");
    }

    @AfterEach
    void tearDown() {
        cmd(writeExec).flushall();
        cmd(readExec).flushall();
        writeExec.close();
        readExec.close();
    }

    @Test
    void write_landsOnWritePool_notOnReadPool() {
        ShardedRecord rec = new ShardedRecord("dev-rr", 0, RecordType.EVENT,
                "evt-1", "{}", System.currentTimeMillis());
        store.write(rec);

        // The record hash key must exist in PRIMARY (writeExec).
        long primaryKeys = cmd(writeExec).dbsize();
        assertThat(primaryKeys).isGreaterThan(0);

        // REPLICA is untouched — store did not write there.
        assertThat(cmd(readExec).dbsize()).isZero();
    }

    @Test
    void readDevice_goesToReadPool_emptyWhenReplicaHasNoData() {
        // Write a record so PRIMARY has data.
        ShardedRecord rec = new ShardedRecord("dev-rr2", 0, RecordType.EVENT,
                "evt-2", "{}", System.currentTimeMillis());
        store.write(rec);

        // readDevice reads from readExec (REPLICA), which is empty → no results.
        Page<ShardedRecord> page = store.readDevice("dev-rr2", ShardCursor.start(), 10);
        assertThat(page.records()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void readDevice_returnsDataWhenReplicaIsPopulated() {
        // Simulate replication by writing directly to both endpoints.
        ShardedRecord rec = new ShardedRecord("dev-rr3", 0, RecordType.EVENT,
                "evt-3", "{}", System.currentTimeMillis());
        store.write(rec);

        // Copy data to replica to simulate async replication catching up.
        RedisCommands<String, String> primary = cmd(writeExec);
        RedisCommands<String, String> replica = cmd(readExec);
        primary.keys("rr:*").forEach(key -> {
            String type = primary.type(key);
            if ("hash".equals(type)) {
                replica.hset(key, primary.hgetall(key));
            } else if ("zset".equals(type)) {
                primary.zrangeWithScores(key, 0, -1)
                       .forEach(sv -> replica.zadd(key, sv.getScore(), sv.getValue()));
            }
        });

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
