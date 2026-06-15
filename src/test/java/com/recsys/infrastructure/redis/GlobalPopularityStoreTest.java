package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.Pool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalPopularityStoreTest {

    private static Pool<Jedis> mockPool(Jedis jedis) {
        Pool<Jedis> pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        return pool;
    }

    @Test
    void getTopIds_returnsIdsFromRedisSortedSetInOrder() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "3", "1"));

        GlobalPopularityStore store = new GlobalPopularityStore(mockPool(jedis));
        List<String> ids = store.getTopIds(3);

        assertThat(ids).containsExactly("5", "3", "1");
        verify(jedis).close();
    }

    @Test
    void getTopIds_emptyWhenRedisKeyMissing() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of());

        List<String> ids = new GlobalPopularityStore(mockPool(jedis)).getTopIds(10);

        assertThat(ids).isEmpty();
    }

    @Test
    void getTopIds_zeroLimitReturnsEmpty() {
        Jedis jedis = mock(Jedis.class);
        List<String> ids = new GlobalPopularityStore(mockPool(jedis)).getTopIds(0);
        assertThat(ids).isEmpty();
    }
}
