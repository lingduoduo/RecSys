package com.recsys.features;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisEmbeddingStoreTest {

    @Test
    void jitteredTtlMillis_neverShortensBaseTtlAndCapsJitter() {
        RedisEmbeddingStore store = new RedisEmbeddingStore(null, "emb", 0.10);
        long baseMs = 60_000L;

        for (int i = 0; i < 200; i++) {
            long ttlMs = store.jitteredTtlMillis(60L);
            assertThat(ttlMs).isBetween(baseMs, 66_000L);
        }
    }

    @Test
    void jitteredTtlMillis_returnsExactBaseWhenJitterDisabled() {
        RedisEmbeddingStore store = new RedisEmbeddingStore(null, "emb", 0.0);

        assertThat(store.jitteredTtlMillis(30L)).isEqualTo(30_000L);
    }

    @Test
    void jitteredTtlMillis_preservesNonExpiringTtlSentinel() {
        RedisEmbeddingStore store = new RedisEmbeddingStore(null, "emb", 0.10);

        assertThat(store.jitteredTtlMillis(0L)).isZero();
        assertThat(store.jitteredTtlMillis(-1L)).isEqualTo(-1L);
    }
}
