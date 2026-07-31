package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The store derives its key scheme from the generation it is serving, not from config. */
class ShardedRecordStoreGenerationKeyTest {

    @Test
    void keysFollowTheGenerationsOwnFormat() {
        assertThat(ShardKeys.of("sr:", new ShardTopology(1, 2, 150, 0L)).stream(0))
                .isEqualTo("sr:stream:0");
        assertThat(ShardKeys.of("sr:", new ShardTopology(2, 4, 150, 0L, ShardKeys.FORMAT_TAGGED))
                .stream(1)).isEqualTo("sr:g2:stream:{1}");
    }
}
