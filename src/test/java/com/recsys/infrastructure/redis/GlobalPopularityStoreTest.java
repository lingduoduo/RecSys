package com.recsys.infrastructure.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.util.Pool;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    void getTopIds_cachesWithinFreshTtl_singleRedisRead() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "3", "1"));

        AtomicLong clock = new AtomicLong(0);
        GlobalPopularityStore store =
                new GlobalPopularityStore(mockPool(jedis), 1_000L, 60_000L, clock::get);

        assertThat(store.getTopIds(3)).containsExactly("5", "3", "1");
        assertThat(store.getTopIds(3)).containsExactly("5", "3", "1"); // within TTL

        verify(jedis, times(1)).zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong());
    }

    @Test
    void getTopIds_slicesTopNSnapshotByLimit_oneRedisRead() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "4", "3", "2", "1"));

        AtomicLong clock = new AtomicLong(0);
        GlobalPopularityStore store =
                new GlobalPopularityStore(mockPool(jedis), 1_000L, 60_000L, clock::get);

        assertThat(store.getTopIds(2)).containsExactly("5", "4");
        assertThat(store.getTopIds(4)).containsExactly("5", "4", "3", "2"); // shared snapshot

        verify(jedis, times(1)).zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong());
    }

    @Test
    void getTopIds_servesStaleOnRedisErrorThenEmptyBeyondStale() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "3"))                 // first load OK
                .thenThrow(new JedisException("down"));         // subsequent loads fail

        AtomicLong clock = new AtomicLong(0);
        GlobalPopularityStore store =
                new GlobalPopularityStore(mockPool(jedis), 10L, 100L, clock::get);

        assertThat(store.getTopIds(2)).containsExactly("5", "3"); // t=0 seed

        clock.set(50);                                            // stale window
        assertThat(store.getTopIds(2)).containsExactly("5", "3"); // served stale

        clock.set(200);                                           // beyond stale
        assertThat(store.getTopIds(2)).isEmpty();                 // error → empty (DataManager fallback upstream)
    }

    @Test
    void getTopIds_returnsEmptyWhenRedisDownAndNoSnapshot() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenThrow(new JedisException("down"));

        GlobalPopularityStore store = new GlobalPopularityStore(mockPool(jedis));
        assertThat(store.getTopIds(5)).isEmpty();
    }
}
