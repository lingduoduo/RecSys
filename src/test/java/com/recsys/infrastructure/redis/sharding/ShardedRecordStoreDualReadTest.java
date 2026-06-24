package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
class ShardedRecordStoreDualReadTest extends RedisShardingTestBase {

    private ShardedRecordStore storeOn(ShardTopologyProvider provider) {
        return new ShardedRecordStore(pool, pool, provider, new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void readDevice_findsRecordWrittenUnderPreviousGeneration_whileWindowOpen() {
        // Write under generation 1 (unversioned keys).
        ShardTopologyProvider v1 = ShardTopologyProvider.fixedAtVersion(1, 2, 150);
        storeOn(v1).write(new ShardedRecord("dev-A", 0, RecordType.EVENT, "e1", "p", 1L));

        // Reader on generation 2, with generation 1 still active as previous.
        ShardTopologyProvider migrating = TestProviders.withPrevious(
                /*current*/ new ShardTopology(2, 4, 150, 0L),
                /*previous*/ new ShardTopology(1, 2, 150, 0L),
                /*prevExpiresAtMs*/ Long.MAX_VALUE);

        var page = storeOn(migrating).readDevice("dev-A", ShardCursor.start(), 10);
        assertThat(page.records()).extracting(ShardedRecord::eventId).contains("e1");
    }

    @Test
    void readDevice_skipsPreviousGeneration_afterWindowCloses() {
        ShardTopologyProvider v1 = ShardTopologyProvider.fixedAtVersion(1, 2, 150);
        storeOn(v1).write(new ShardedRecord("dev-B", 0, RecordType.EVENT, "e2", "p", 1L));

        ShardTopologyProvider expired = TestProviders.withPrevious(
                new ShardTopology(2, 4, 150, 0L),
                new ShardTopology(1, 2, 150, 0L),
                /*prevExpiresAtMs*/ Long.MIN_VALUE); // already closed

        var page = storeOn(expired).readDevice("dev-B", ShardCursor.start(), 10);
        assertThat(page.records()).extracting(ShardedRecord::eventId).doesNotContain("e2");
    }
}
