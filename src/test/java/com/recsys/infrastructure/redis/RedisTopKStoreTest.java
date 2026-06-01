package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTopKStoreTest {

    @Test
    void getTopKIds_cachesFullWindowListWithinTtl() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        // Store now fetches up to MAX_FULL_CACHE_SIZE=100 items per window
        when(jedis.zrevrange("topk:last_hour", 0, 99)).thenReturn(List.of("1", "2", "3"));
        RedisTopKStore store = new RedisTopKStore(pool, "topk:", 5_000L);

        assertThat(store.getTopKIds("last_hour", 5)).containsExactly("1", "2", "3");
        assertThat(store.getTopKIds("last_hour", 5)).containsExactly("1", "2", "3");

        // Only one Redis fetch regardless of how many calls share the same window
        verify(jedis, times(1)).zrevrange("topk:last_hour", 0, 99);
        assertThat(store.hotCacheSize()).isEqualTo(1);
    }

    @Test
    void getTopKIds_differentKValuesShareOneCacheEntry() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.zrevrange("topk:last_hour", 0, 99))
                .thenReturn(List.of("1", "2", "3", "4", "5"));
        RedisTopKStore store = new RedisTopKStore(pool, "topk:", 5_000L);

        List<String> k2 = store.getTopKIds("last_hour", 2);
        List<String> k4 = store.getTopKIds("last_hour", 4);

        assertThat(k2).containsExactly("1", "2");
        assertThat(k4).containsExactly("1", "2", "3", "4");
        // Both k values share one Redis round-trip and one cache entry
        verify(jedis, times(1)).zrevrange("topk:last_hour", 0, 99);
        assertThat(store.hotCacheSize()).isEqualTo(1);
    }

    @Test
    void getTopKIds_canDisableLocalCache() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.zrevrange("topk:last_day", 0, 99)).thenReturn(List.of("5", "6"));
        RedisTopKStore store = new RedisTopKStore(pool, "topk:", 0L);

        store.getTopKIds("last_day", 2);
        store.getTopKIds("last_day", 2);

        verify(jedis, times(2)).zrevrange("topk:last_day", 0, 99);
        assertThat(store.hotCacheSize()).isZero();
    }

    @Test
    void getTopKIds_nonPositiveK_returnsEmptyWithoutRedisCall() {
        JedisPool pool = mock(JedisPool.class);
        RedisTopKStore store = new RedisTopKStore(pool, "topk:", 5_000L);

        assertThat(store.getTopKIds("last_hour", 0)).isEmpty();

        verify(pool, times(0)).getResource();
    }

    @Test
    void getTopKIds_deduplicatesConcurrentMissesForSameWindow() throws Exception {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        CountDownLatch redisEntered = new CountDownLatch(1);
        CountDownLatch releaseRedis = new CountDownLatch(1);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.zrevrange("topk:last_hour", 0, 99)).thenAnswer(invocation -> {
            redisEntered.countDown();
            assertThat(releaseRedis.await(1, TimeUnit.SECONDS)).isTrue();
            return List.of("1", "2", "3");
        });
        RedisTopKStore store = new RedisTopKStore(pool, "topk:", 5_000L);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> store.getTopKIds("last_hour", 2));
            assertThat(redisEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> store.getTopKIds("last_hour", 2));

            releaseRedis.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS)).containsExactly("1", "2");
            assertThat(second.get(1, TimeUnit.SECONDS)).containsExactly("1", "2");
            verify(jedis, times(1)).zrevrange("topk:last_hour", 0, 99);
        } finally {
            releaseRedis.countDown();
            executor.shutdownNow();
        }
    }
}
