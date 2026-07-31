package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardKeysTest {

    @Test
    void format1_generation1_usesOriginalUnversionedUntaggedKeys() {
        ShardKeys keys = new ShardKeys("sr:", 1, ShardKeys.FORMAT_UNTAGGED);
        assertThat(keys.rec(0, 5L)).isEqualTo("sr:rec:0:5");
        assertThat(keys.dev(0, "dev-1")).isEqualTo("sr:dev:0:dev-1");
        assertThat(keys.stream(0)).isEqualTo("sr:stream:0");
        assertThat(keys.seq(0)).isEqualTo("sr:seq:0");
    }

    @Test
    void format1_generation2_prependsGenerationPrefixOnly() {
        ShardKeys keys = new ShardKeys("sr:", 2, ShardKeys.FORMAT_UNTAGGED);
        assertThat(keys.rec(1, 7L)).isEqualTo("sr:g2:rec:1:7");
        assertThat(keys.dev(1, "dev-1")).isEqualTo("sr:g2:dev:1:dev-1");
        assertThat(keys.stream(1)).isEqualTo("sr:g2:stream:1");
        assertThat(keys.seq(1)).isEqualTo("sr:g2:seq:1");
    }

    @Test
    void format2_wrapsTheShardIndexInAHashTag() {
        ShardKeys keys = new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED);
        assertThat(keys.rec(1, 7L)).isEqualTo("sr:g3:rec:{1}:7");
        assertThat(keys.dev(1, "dev-1")).isEqualTo("sr:g3:dev:{1}:dev-1");
        assertThat(keys.stream(1)).isEqualTo("sr:g3:stream:{1}");
        assertThat(keys.seq(1)).isEqualTo("sr:g3:seq:{1}");
    }

    @Test
    void allKeysOfOneShardShareTheSameHashTagUnderFormat2() {
        ShardKeys keys = new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED);
        assertThat(tagOf(keys.rec(2, 1L)))
                .isEqualTo(tagOf(keys.dev(2, "d")))
                .isEqualTo(tagOf(keys.stream(2)))
                .isEqualTo(tagOf(keys.seq(2)));
    }

    @Test
    void differentShardsGetDifferentHashTags() {
        ShardKeys keys = new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED);
        assertThat(tagOf(keys.stream(0))).isNotEqualTo(tagOf(keys.stream(1)));
    }

    @Test
    void recPrefixConcatenatedWithASequenceEqualsTheRecordKey() {
        ShardKeys tagged = new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED);
        assertThat(tagged.recPrefix(1) + 7L).isEqualTo(tagged.rec(1, 7L));

        ShardKeys untagged = new ShardKeys("sr:", 1, ShardKeys.FORMAT_UNTAGGED);
        assertThat(untagged.recPrefix(0) + 5L).isEqualTo(untagged.rec(0, 5L));
    }

    @Test
    void devScanPatternMatchesTheDeviceKeyNamespace() {
        assertThat(new ShardKeys("sr:", 1, ShardKeys.FORMAT_UNTAGGED).devScanPattern(0))
                .isEqualTo("sr:dev:0:*");
        assertThat(new ShardKeys("sr:", 3, ShardKeys.FORMAT_TAGGED).devScanPattern(0))
                .isEqualTo("sr:g3:dev:{0}:*");
    }

    @Test
    void ofReadsVersionAndFormatFromTheTopology() {
        ShardKeys keys = ShardKeys.of("sr:", new ShardTopology(4, 2, 150, 0L, ShardKeys.FORMAT_TAGGED));
        assertThat(keys.version()).isEqualTo(4);
        assertThat(keys.keyFormat()).isEqualTo(ShardKeys.FORMAT_TAGGED);
        assertThat(keys.stream(0)).isEqualTo("sr:g4:stream:{0}");
    }

    @Test
    void anUnknownKeyFormatIsRejected() {
        assertThatThrownBy(() -> new ShardKeys("sr:", 1, 99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // The substring between the first '{' and the following '}' — what Redis Cluster hashes.
    private static String tagOf(String key) {
        int open = key.indexOf('{');
        return key.substring(open + 1, key.indexOf('}', open));
    }
}
