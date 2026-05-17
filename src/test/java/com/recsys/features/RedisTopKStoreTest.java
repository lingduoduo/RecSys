package com.recsys.features;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTopKStoreTest {

    @Test
    void getTopKIds_cachesHotWindowWithinTtl() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.zrevrange("topk:last_hour", 0, 4)).thenReturn(List.of("1", "2", "3"));
        RedisTopKStore store = new RedisTopKStore(pool, "topk:", 5_000L);

        assertThat(store.getTopKIds("last_hour", 5)).containsExactly("1", "2", "3");
        assertThat(store.getTopKIds("last_hour", 5)).containsExactly("1", "2", "3");

        verify(jedis, times(1)).zrevrange("topk:last_hour", 0, 4);
        assertThat(store.hotCacheSize()).isEqualTo(1);
    }

    @Test
    void getTopKIds_canDisableLocalCache() {
        Jedis jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.zrevrange("topk:last_day", 0, 1)).thenReturn(List.of("5", "6"));
        RedisTopKStore store = new RedisTopKStore(pool, "topk:", 0L);

        store.getTopKIds("last_day", 2);
        store.getTopKIds("last_day", 2);

        verify(jedis, times(2)).zrevrange("topk:last_day", 0, 1);
        assertThat(store.hotCacheSize()).isZero();
    }
}
