package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    @Test
    void fnv1a64_matchesGoldenValuesFromCurrentImplementation() {
        assertThat(Hashing.fnv1a64("v0:0")).isEqualTo(-6049490985673087943L);
        assertThat(Hashing.fnv1a64("v0:1")).isEqualTo(-6049492085184716154L);
        assertThat(Hashing.fnv1a64("device-123")).isEqualTo(8014270626680959582L);
        assertThat(Hashing.fnv1a64("user:12345")).isEqualTo(5900300057503531651L);
        assertThat(Hashing.fnv1a64("")).isEqualTo(-3750763034362895579L);
    }

    @Test
    void fnv1a64_stringAndByteOverloadsAgree() {
        assertThat(Hashing.fnv1a64("device-123"))
                .isEqualTo(Hashing.fnv1a64("device-123".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fnv1a64_isDeterministicForDistinctShortKeys() {
        assertThat(Hashing.fnv1a64("a")).isEqualTo(Hashing.fnv1a64("a"));
        assertThat(Hashing.fnv1a64("a")).isNotEqualTo(Hashing.fnv1a64("b"));
    }

    @Test
    void fmix64_matchesBucketerAvalancheGoldenValues() {
        assertThat(Hashing.fmix64(Hashing.fnv1a64("123:default"))).isEqualTo(-5946069669134103219L);
        assertThat(Hashing.fmix64(Hashing.fnv1a64(":"))).isEqualTo(8049451291064401709L);
    }
}
