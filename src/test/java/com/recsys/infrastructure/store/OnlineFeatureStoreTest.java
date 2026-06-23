package com.recsys.infrastructure.store;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    void getRecentMovieIds_servesBoundedStaleValueWhenRedisFails() throws Exception {
        var stub = new RedisPoolStub();
        when(stub.pool.getResource()).thenReturn(stub.jedis);
        when(stub.jedis.get("user:1:recent_movies"))
                .thenReturn("10 20")
                .thenThrow(new IllegalStateException("redis down"));
        var store = new OnlineFeatureStore(stub.pool, 1L, 5_000L, 100);

        assertThat(store.getRecentMovieIds(1, 10)).containsExactly(10, 20);
        Thread.sleep(5);

        assertThat(store.getRecentMovieIds(1, 10)).containsExactly(10, 20);
        verify(stub.jedis, times(2)).get("user:1:recent_movies");
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

    @Test
    void getFeature_cachesNullSentinelWithinTtl() {
        var stub = new RedisPoolStub();
        when(stub.pool.getResource()).thenReturn(stub.jedis);
        when(stub.jedis.get("user:9:embedding")).thenReturn(null);
        var store = new OnlineFeatureStore(stub.pool, 5_000L);

        assertThat(store.getFeature("user:9:embedding")).isNull();
        assertThat(store.getFeature("user:9:embedding")).isNull();

        verify(stub.jedis, times(1)).get("user:9:embedding");
    }

    @Test
    void getFeatures_deduplicatesChunksAndCachesRedisMget() {
        var stub = new RedisPoolStub();
        when(stub.pool.getResource()).thenReturn(stub.jedis);
        when(stub.jedis.mget("user:1:embedding", "movie:7:engagement")).thenReturn(List.of("0.1 0.2", "0.42"));
        when(stub.jedis.mget("session:abc:features")).thenReturn(List.of("fresh"));
        var store = new OnlineFeatureStore(stub.pool, 5_000L, 100, 2);

        Map<String, String> first = store.getFeatures(List.of(
                "user:1:embedding",
                "user:1:embedding",
                "movie:7:engagement",
                "session:abc:features"
        ));
        Map<String, String> second = store.getFeatures(List.of("user:1:embedding", "movie:7:engagement", "session:abc:features"));

        assertThat(first).containsEntry("user:1:embedding", "0.1 0.2")
                .containsEntry("movie:7:engagement", "0.42")
                .containsEntry("session:abc:features", "fresh");
        assertThat(second).isEqualTo(first);
        verify(stub.jedis, times(1)).mget("user:1:embedding", "movie:7:engagement");
        verify(stub.jedis, times(1)).mget("session:abc:features");
    }

    @Test
    void getFeatures_cachesMissingFeatureKeys() {
        var stub = new RedisPoolStub();
        when(stub.pool.getResource()).thenReturn(stub.jedis);
        when(stub.jedis.mget("trend:hourly")).thenReturn(Collections.singletonList(null));
        var store = new OnlineFeatureStore(stub.pool, 5_000L, 100, 10);

        assertThat(store.getFeatures(List.of("trend:hourly"))).isEmpty();
        assertThat(store.getFeatures(List.of("trend:hourly"))).isEmpty();

        verify(stub.jedis, times(1)).mget("trend:hourly");
    }

    @Test
    void getFeatures_servesStaleValueWhenRedisFailsOnBatchPath() throws Exception {
        var stub = new RedisPoolStub();
        when(stub.pool.getResource()).thenReturn(stub.jedis);
        // First call succeeds and populates cache
        when(stub.jedis.mget("user:1:embedding")).thenReturn(List.of("0.1 0.2"));
        var store = new OnlineFeatureStore(stub.pool, 1L, 5_000L, 100, 10);

        // Warm the cache
        Map<String, String> first = store.getFeatures(List.of("user:1:embedding"));
        assertThat(first).containsEntry("user:1:embedding", "0.1 0.2");

        // Redis goes down after TTL expires
        Thread.sleep(5);
        when(stub.jedis.mget("user:1:embedding"))
                .thenThrow(new RuntimeException("redis down"));

        // Should serve stale value within staleTtlMs (5000 ms), not throw
        Map<String, String> stale = store.getFeatures(List.of("user:1:embedding"));
        assertThat(stale).containsEntry("user:1:embedding", "0.1 0.2");
    }

    // ── minimal Jedis/JedisPool stub ──────────────────────────────────────

    private static final class RedisPoolStub {
        final redis.clients.jedis.Jedis jedis = mock(redis.clients.jedis.Jedis.class);
        final redis.clients.jedis.util.Pool<redis.clients.jedis.Jedis> pool = mock(redis.clients.jedis.JedisPool.class);

        static RedisPoolStub withHistory(int userId, String value) {
            var stub = new RedisPoolStub();
            when(stub.pool.getResource()).thenReturn(stub.jedis);
            when(stub.jedis.get("user:" + userId + ":recent_movies")).thenReturn(value);
            return stub;
        }
    }
}
