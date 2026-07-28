package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
class ShardedRecordStoreReadTest extends RedisShardingTestBase {

    private ShardedRecordStore store;

    @BeforeEach
    void setUp() {
        var ring = new ConsistentHashRing(2, 150);
        store = new ShardedRecordStore(exec, ring, new SequenceGenerator(exec, "sr:"), "sr:");
    }

    private ShardedRecord event(String deviceId, String eventId) {
        return new ShardedRecord(deviceId, 0, RecordType.EVENT, eventId, "{}", System.currentTimeMillis());
    }

    @Test
    void readDevice_emptyDevice_returnsEmptyPage() {
        Page<ShardedRecord> page = store.readDevice("ghost-device", ShardCursor.start(), 10);
        assertThat(page.records()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void readDevice_returnsRecordsInSeqOrder() {
        store.write(event("dev-A", "e1"));
        store.write(event("dev-A", "e2"));
        store.write(event("dev-A", "e3"));

        Page<ShardedRecord> page = store.readDevice("dev-A", ShardCursor.start(), 10);

        assertThat(page.records()).hasSize(3);
        List<Long> seqs = page.records().stream().map(ShardedRecord::seqNum).toList();
        assertThat(seqs).isSorted();
    }

    @Test
    void readDevice_respectsLimitAndCursorAdvances() {
        for (int i = 1; i <= 5; i++) store.write(event("dev-B", "e" + i));

        Page<ShardedRecord> page1 = store.readDevice("dev-B", ShardCursor.start(), 2);
        assertThat(page1.records()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();

        Page<ShardedRecord> page2 = store.readDevice("dev-B", page1.next(), 2);
        assertThat(page2.records()).hasSize(2);

        Page<ShardedRecord> page3 = store.readDevice("dev-B", page2.next(), 2);
        assertThat(page3.records()).hasSize(1);
        assertThat(page3.hasMore()).isFalse();
    }

    @Test
    void readShard_returnsAllRecordsAcrossDevices() {
        var ring = new ConsistentHashRing(1, 150); // single shard — all devices land here
        var s = new ShardedRecordStore(exec, ring, new SequenceGenerator(exec, "sr:"), "sr:");

        s.write(event("dev-X", "ex1"));
        s.write(event("dev-Y", "ey1"));
        s.write(event("dev-X", "ex2"));

        Page<ShardedRecord> page = s.readShard(0, ShardCursor.start(), 10);
        assertThat(page.records()).hasSize(3);
    }

    @Test
    void readShard_cursorAdvancesIncrementally() {
        var ring = new ConsistentHashRing(1, 150);
        var s = new ShardedRecordStore(exec, ring, new SequenceGenerator(exec, "sr:"), "sr:");

        for (int i = 1; i <= 4; i++) s.write(event("dev-C", "ec" + i));

        Page<ShardedRecord> p1 = s.readShard(0, ShardCursor.start(), 2);
        assertThat(p1.records()).hasSize(2);
        assertThat(p1.hasMore()).isTrue();

        Page<ShardedRecord> p2 = s.readShard(0, p1.next(), 2);
        assertThat(p2.records()).hasSize(2);
        assertThat(p2.hasMore()).isFalse();
    }

    /**
     * Replaces {@code readAllShards_advancesEachShardIndependently}. That method was deleted
     * on 2026-07-28 (no production caller, unbounded drain); walking every shard is now done
     * by paging {@code readShard} per shard, which is what the HTTP surface already did.
     */
    @Test
    void pagingEveryShardWithReadShard_visitsEveryRecordExactlyOnce() {
        var ring = new ConsistentHashRing(2, 150);
        var s = new ShardedRecordStore(exec, ring, new SequenceGenerator(exec, "sr:"), "sr:");

        for (int i = 0; i < 20; i++) s.write(event("device-" + i, "e" + i));

        int total = 0;
        for (int shard = 0; shard < 2; shard++) {
            ShardCursor cursor = ShardCursor.start();
            Page<ShardedRecord> page;
            do {
                page = s.readShard(shard, cursor, 5);
                total += page.records().size();
                cursor = page.next();
            } while (page.hasMore());
        }

        assertThat(total).isEqualTo(20);
    }
}
