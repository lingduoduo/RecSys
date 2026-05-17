package com.recsys.streaming;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OnlineFeatureStoreTest {

    @Test
    void getRecentMovieIds_cachesRedisResultWithinTtl() throws Exception {
        var pool = RedisPoolStub.withHistory(1, "10 20 30");
        var store = new OnlineFeatureStore(pool.pool,5_000L);

        List<Integer> first  = store.getRecentMovieIds(1, 10);
        List<Integer> second = store.getRecentMovieIds(1, 10);

        assertThat(first).containsExactly(10, 20, 30);
        assertThat(second).isEqualTo(first);
        // Redis was only touched once (second call is a cache hit)
        verify(pool.jedis, times(1)).get("user:1:recent_movies");
    }

    @Test
    void getRecentMovieIds_refetchesAfterTtlExpiry() throws Exception {
        var pool = RedisPoolStub.withHistory(1, "10 20 30");
        var store = new OnlineFeatureStore(pool.pool,1L); // 1 ms TTL

        store.getRecentMovieIds(1, 10);
        Thread.sleep(5);
        store.getRecentMovieIds(1, 10);

        verify(pool.jedis, times(2)).get("user:1:recent_movies");
    }

    @Test
    void getRecentMovieIds_appliesLimitFromCache() {
        var pool = RedisPoolStub.withHistory(2, "5 6 7 8 9");
        var store = new OnlineFeatureStore(pool.pool,5_000L);

        List<Integer> result = store.getRecentMovieIds(2, 3);

        // limit returns the LAST k entries (most recent)
        assertThat(result).hasSize(3).containsExactly(7, 8, 9);
    }

    @Test
    void getRecentMovieIds_emptyRedisValue_returnsEmpty() {
        var pool = RedisPoolStub.withHistory(3, null);
        var store = new OnlineFeatureStore(pool.pool,5_000L);

        assertThat(store.getRecentMovieIds(3, 5)).isEmpty();
    }

    @Test
    void getRecentMovieIds_malformedTokensIgnored() {
        var pool = RedisPoolStub.withHistory(4, "1 bad 3");
        var store = new OnlineFeatureStore(pool.pool,5_000L);

        assertThat(store.getRecentMovieIds(4, 10)).containsExactly(1, 3);
    }

    @Test
    void getRecentMovieIds_evictsWhenHotUserCacheExceedsLimit() {
        var stub = new RedisPoolStub();
        when(stub.pool.getResource()).thenReturn(stub.jedis);
        when(stub.jedis.get("user:1:recent_movies")).thenReturn("1");
        when(stub.jedis.get("user:2:recent_movies")).thenReturn("2");
        when(stub.jedis.get("user:3:recent_movies")).thenReturn("3");
        var store = new OnlineFeatureStore(stub.pool, 5_000L, 2);

        store.getRecentMovieIds(1, 10);
        store.getRecentMovieIds(2, 10);
        store.getRecentMovieIds(3, 10);

        assertThat(store.cacheSize()).isLessThanOrEqualTo(2);
    }

    // ── minimal Jedis/JedisPool stub ──────────────────────────────────────

    private static final class RedisPoolStub {
        final redis.clients.jedis.Jedis jedis = mock(redis.clients.jedis.Jedis.class);
        final redis.clients.jedis.JedisPool pool = mock(redis.clients.jedis.JedisPool.class);

        static RedisPoolStub withHistory(int userId, String value) {
            var stub = new RedisPoolStub();
            when(stub.pool.getResource()).thenReturn(stub.jedis);
            when(stub.jedis.get("user:" + userId + ":recent_movies")).thenReturn(value);
            return stub;
        }
    }
}
