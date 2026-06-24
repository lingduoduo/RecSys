package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardTopologyTest {

    @Test
    void buildsRingAndExposesFields() {
        ShardTopology t = new ShardTopology(1, 4, 150, 1000L);
        assertThat(t.version()).isEqualTo(1);
        assertThat(t.shardCount()).isEqualTo(4);
        assertThat(t.vnodes()).isEqualTo(150);
        assertThat(t.createdAtMs()).isEqualTo(1000L);
        assertThat(t.shardFor("device-1")).isBetween(0, 3);
    }

    @Test
    void shardForMatchesUnderlyingRing() {
        ShardTopology t = new ShardTopology(2, 8, 150, 0L);
        assertThat(t.shardFor("user:123")).isEqualTo(t.ring().shardFor("user:123"));
    }

    @Test
    void rejectsInvalidShardCount() {
        assertThatThrownBy(() -> new ShardTopology(1, 0, 150, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyPrefix_v1IsEmpty_v2Plus_isPrefixed() {
        assertThat(Generations.keyPrefix(1)).isEmpty();
        assertThat(Generations.keyPrefix(0)).isEmpty();
        assertThat(Generations.keyPrefix(2)).isEqualTo("g2:");
        assertThat(Generations.keyPrefix(7)).isEqualTo("g7:");
    }
}
