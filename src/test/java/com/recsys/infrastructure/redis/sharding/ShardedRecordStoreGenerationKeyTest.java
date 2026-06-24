package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShardedRecordStoreGenerationKeyTest {

    // Key helpers don't touch Redis; mock pools satisfy the constructor's non-null checks.
    @SuppressWarnings("unchecked")
    private ShardedRecordStore storeAtVersion(int version, int shardCount) {
        Pool<Jedis> pool = mock(Pool.class);
        ShardTopologyProvider provider = ShardTopologyProvider.fixedAtVersion(version, shardCount, 150);
        return new ShardedRecordStore(pool, pool, provider,
                new SequenceGenerator(pool, "sr:"), "sr:");
    }

    @Test
    void version1_usesUnversionedKeys() {
        ShardedRecordStore store = storeAtVersion(1, 2);
        assertThat(store.recKey(1, 0, 5L)).isEqualTo("sr:rec:0:5");
        assertThat(store.devKey(1, 0, "dev-1")).isEqualTo("sr:dev:0:dev-1");
        assertThat(store.streamKey(1, 0)).isEqualTo("sr:stream:0");
    }

    @Test
    void version2_prependsGenerationPrefix() {
        ShardedRecordStore store = storeAtVersion(2, 4);
        assertThat(store.recKey(2, 1, 7L)).isEqualTo("sr:g2:rec:1:7");
        assertThat(store.devKey(2, 1, "dev-1")).isEqualTo("sr:g2:dev:1:dev-1");
        assertThat(store.streamKey(2, 1)).isEqualTo("sr:g2:stream:1");
    }
}
