package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void getEmbeddings_deduplicatesAndChunksRedisMget() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.mget("emb:1", "emb:2")).thenReturn(List.of("1.0 0.0", "0.0 1.0"));
        when(jedis.mget("emb:3")).thenReturn(List.of("0.5 0.5"));

        RedisEmbeddingStore store = new RedisEmbeddingStore(pool, "emb", 0.0, 2);

        Map<Integer, float[]> result = store.getEmbeddings(List.of(1, 1, 2, 3));

        assertThat(result).containsOnlyKeys(1, 2, 3);
        assertThat(result.get(1)).containsExactly(1.0f, 0.0f);
        assertThat(result.get(2)).containsExactly(0.0f, 1.0f);
        assertThat(result.get(3)).containsExactly(0.5f, 0.5f);
        verify(jedis).mget("emb:1", "emb:2");
        verify(jedis).mget("emb:3");
    }
}
