package com.recsys.infrastructure.redis.sharding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backward compatibility of the shard:topology document. A snapshot written before the
 * keyFormat field existed must still parse, and must read as the untagged format.
 */
class ShardTopologySnapshotFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void legacyJsonWithoutKeyFormatReadsAsUntagged() throws Exception {
        String legacy = """
                {"version":1,"shardCount":2,"vnodes":150,"createdAtMs":1000,
                 "prevVersion":null,"prevShardCount":null,"prevExpiresAtMs":null}""";

        ShardTopologyStore.Snapshot snapshot =
                MAPPER.readValue(legacy, ShardTopologyStore.Snapshot.class);

        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.effectiveKeyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
        assertThat(snapshot.effectivePrevKeyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
    }

    @Test
    void keyFormatRoundTrips() throws Exception {
        ShardTopologyStore.Snapshot original = new ShardTopologyStore.Snapshot(
                3, 4, 150, 2000L, 2, 2, 9000L, ShardKeys.FORMAT_TAGGED, ShardKeys.FORMAT_UNTAGGED);

        ShardTopologyStore.Snapshot parsed = MAPPER.readValue(
                MAPPER.writeValueAsString(original), ShardTopologyStore.Snapshot.class);

        assertThat(parsed.effectiveKeyFormat()).isEqualTo(ShardKeys.FORMAT_TAGGED);
        assertThat(parsed.effectivePrevKeyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
    }

    @Test
    void theStoresMapperIgnoresUnknownFields() {
        // A newer writer may add fields this build does not know. The store must keep parsing,
        // or an old pod fails-static on its last-good topology and stops seeing updates.
        String futureJson = """
                {"version":1,"shardCount":2,"vnodes":150,"createdAtMs":1000,
                 "prevVersion":null,"prevShardCount":null,"prevExpiresAtMs":null,
                 "somethingAddedLater":"x"}""";

        assertThat(ShardTopologyStore.parseForTest(futureJson).version()).isEqualTo(1);
    }
}
