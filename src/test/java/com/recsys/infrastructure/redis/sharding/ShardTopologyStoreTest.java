package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
class ShardTopologyStoreTest extends RedisShardingTestBase {

    @Test
    void bootstrap_writesVersion1_andIsIdempotent() {
        ShardTopologyStore store = new ShardTopologyStore(pool, "shard:topology:test1");

        ShardTopologyStore.Snapshot s1 = store.bootstrap(2, 150, 1000L);
        assertThat(s1.version()).isEqualTo(1);
        assertThat(s1.shardCount()).isEqualTo(2);
        assertThat(s1.prevVersion()).isNull();

        // Second bootstrap must NOT overwrite (SETNX) — even with a different shardCount.
        ShardTopologyStore.Snapshot s2 = store.bootstrap(9, 150, 2000L);
        assertThat(s2.version()).isEqualTo(1);
        assertThat(s2.shardCount()).isEqualTo(2);
    }

    @Test
    void load_returnsNullWhenAbsent() {
        ShardTopologyStore store = new ShardTopologyStore(pool, "shard:topology:absent");
        assertThat(store.load()).isNull();
    }

    @Test
    void publishReshard_bumpsVersionAndRecordsPreviousWithExpiry() {
        ShardTopologyStore store = new ShardTopologyStore(pool, "shard:topology:test2");
        store.bootstrap(2, 150, 1000L);

        ShardTopologyStore.Snapshot v2 = store.publishReshard(4, 5000L, 60_000L);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.shardCount()).isEqualTo(4);
        assertThat(v2.prevVersion()).isEqualTo(1);
        assertThat(v2.prevShardCount()).isEqualTo(2);
        assertThat(v2.prevExpiresAtMs()).isEqualTo(65_000L);

        // A subsequent load reflects the published version.
        assertThat(store.load().version()).isEqualTo(2);
    }
}
