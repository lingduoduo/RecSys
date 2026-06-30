package com.recsys.infrastructure.redis.sharding;

import com.recsys.infrastructure.redis.RedisExecutor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShardedRecordStoreGenerationKeyTest {

    // Key helpers don't touch Redis; a mock executor satisfies the constructor's non-null checks.
    private ShardedRecordStore storeAtVersion(int version, int shardCount) {
        RedisExecutor exec = mock(RedisExecutor.class);
        ShardTopologyProvider provider = ShardTopologyProvider.fixedAtVersion(version, shardCount, 150);
        return new ShardedRecordStore(exec, exec, provider,
                new SequenceGenerator(exec, "sr:"), "sr:");
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
