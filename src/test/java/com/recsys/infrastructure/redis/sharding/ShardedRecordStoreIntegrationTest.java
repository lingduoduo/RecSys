package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedRecordStoreIntegrationTest extends RedisShardingTestBase {

    private ShardedRecordStore store;
    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring  = new ConsistentHashRing(1, 150); // single shard for easy assertions
        store = new ShardedRecordStore(pool, ring, new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void fullRoundTrip_eventRecord() {
        ShardedRecord input = new ShardedRecord("user:42", 0, RecordType.EVENT,
                "click-001", "{\"movieId\":7}", System.currentTimeMillis());

        WriteResult wr = store.write(input);
        Page<ShardedRecord> page = store.readDevice("user:42", ShardCursor.start(), 10);

        assertThat(page.records()).hasSize(1);
        ShardedRecord out = page.records().get(0);
        assertThat(out.deviceId()).isEqualTo("user:42");
        assertThat(out.type()).isEqualTo(RecordType.EVENT);
        assertThat(out.eventId()).isEqualTo("click-001");
        assertThat(out.payload()).isEqualTo("{\"movieId\":7}");
        assertThat(out.seqNum()).isEqualTo(wr.seqNum());
    }

    @Test
    void fullRoundTrip_featureAndLogTypes() {
        store.write(new ShardedRecord("user:1", 0, RecordType.FEATURE,
                "ctr-001", "{\"ctr\":0.2}", System.currentTimeMillis()));
        store.write(new ShardedRecord("user:1", 0, RecordType.LOG,
                "log-001", "startup", System.currentTimeMillis()));

        Page<ShardedRecord> page = store.readDevice("user:1", ShardCursor.start(), 10);
        List<RecordType> types = page.records().stream().map(ShardedRecord::type).toList();
        assertThat(types).containsExactlyInAnyOrder(RecordType.FEATURE, RecordType.LOG);
    }

    @Test
    void concurrentWrites_noDuplicateSeqNums() throws InterruptedException {
        int threads = 10, writesPerThread = 100;
        Set<Long> seqNums = new ConcurrentSkipListSet<>();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService ex = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            int tid = t;
            ex.submit(() -> {
                for (int i = 0; i < writesPerThread; i++) {
                    WriteResult wr = store.write(new ShardedRecord(
                            "dev-" + tid, 0, RecordType.EVENT,
                            "e-" + tid + "-" + i, "{}", System.currentTimeMillis()));
                    seqNums.add(wr.seqNum());
                }
                latch.countDown();
            });
        }
        latch.await();
        ex.shutdown();

        assertThat(seqNums).hasSize(threads * writesPerThread);
    }

    @Test
    void shardLevelScan_returnsAllRecordsWrittenByPerDeviceWrites() {
        store.write(new ShardedRecord("dev-P", 0, RecordType.EVENT, "p1", "{}", System.currentTimeMillis()));
        store.write(new ShardedRecord("dev-Q", 0, RecordType.EVENT, "q1", "{}", System.currentTimeMillis()));
        store.write(new ShardedRecord("dev-P", 0, RecordType.EVENT, "p2", "{}", System.currentTimeMillis()));

        List<ShardedRecord> allFromShard = new ArrayList<>();
        ShardCursor cursor = ShardCursor.start();
        while (true) {
            Page<ShardedRecord> page = store.readShard(0, cursor, 10);
            allFromShard.addAll(page.records());
            if (!page.hasMore()) break;
            cursor = page.next();
        }

        assertThat(allFromShard).hasSize(3);
        List<String> eventIds = allFromShard.stream().map(ShardedRecord::eventId).toList();
        assertThat(eventIds).containsExactlyInAnyOrder("p1", "q1", "p2");
    }

    @Test
    void readDevice_onlySeesOwnRecordsNotOtherDevices() {
        store.write(new ShardedRecord("user:A", 0, RecordType.EVENT, "a1", "{}", System.currentTimeMillis()));
        store.write(new ShardedRecord("user:B", 0, RecordType.EVENT, "b1", "{}", System.currentTimeMillis()));
        store.write(new ShardedRecord("user:A", 0, RecordType.EVENT, "a2", "{}", System.currentTimeMillis()));

        Page<ShardedRecord> page = store.readDevice("user:A", ShardCursor.start(), 10);

        assertThat(page.records()).hasSize(2);
        assertThat(page.records()).allMatch(r -> r.deviceId().equals("user:A"));
    }
}
