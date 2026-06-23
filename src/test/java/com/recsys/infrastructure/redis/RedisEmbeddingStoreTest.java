package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.util.Pool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
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
        Pool<Jedis> pool = mock(JedisPool.class);
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

    @Test
    @SuppressWarnings("unchecked")
    void loadAll_stopsWhenTimeBudgetExceeded() {
        Pool<Jedis> pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);

        AtomicLong now = new AtomicLong(0L);
        LongSupplier clock = () -> now.getAndAdd(1000L); // each read advances 1s
        RedisEmbeddingStore store =
                new RedisEmbeddingStore(pool, "emb", 0.0, 500, 500L, clock); // 500ms budget

        ScanResult<String> page = mock(ScanResult.class);
        when(page.getResult()).thenReturn(List.of("emb:1"));
        when(page.getCursor()).thenReturn("99"); // never "0" -> would loop forever without the budget
        when(jedis.scan(anyString(), any(ScanParams.class))).thenReturn(page);
        when(jedis.mget(any(String[].class))).thenReturn(List.of("1.0 0.0"));

        Map<Integer, float[]> result = store.loadAll();

        assertThat(result).containsKey(1);                                   // partial result returned
        verify(jedis, atMost(2)).scan(anyString(), any(ScanParams.class));   // budget broke the loop
    }
}
