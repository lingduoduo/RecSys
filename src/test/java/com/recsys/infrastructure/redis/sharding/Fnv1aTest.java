package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class Fnv1aTest {

    // Golden values captured from the pre-refactor ConsistentHashRing.fnv1a (Step 1).
    @Test
    void hash_matchesGoldenValues() {
        assertThat(Fnv1a.hash("v0:0")).isEqualTo(-6049490985673087943L);
        assertThat(Fnv1a.hash("v0:1")).isEqualTo(-6049492085184716154L);
        assertThat(Fnv1a.hash("device-123")).isEqualTo(8014270626680959582L);
    }

    @Test
    void stringAndByteOverloadsAgree() {
        assertThat(Fnv1a.hash("hello"))
                .isEqualTo(Fnv1a.hash("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void deterministicAndCollisionFreeForDistinctShortKeys() {
        assertThat(Fnv1a.hash("a")).isEqualTo(Fnv1a.hash("a"));
        assertThat(Fnv1a.hash("a")).isNotEqualTo(Fnv1a.hash("b"));
    }
}
