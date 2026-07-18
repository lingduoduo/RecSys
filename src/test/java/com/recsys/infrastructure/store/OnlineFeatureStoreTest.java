package com.recsys.infrastructure.store;

import com.recsys.infrastructure.redis.RedisExecutor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OnlineFeatureStoreTest {

    @Test
    void primaryRecentMovieReadBypassesCacheAndReplicaRead() {
        var stub = RedisExecutorStub.withHistory(1, "10");
        var store = new OnlineFeatureStore(stub.exec, 5_000L);
        assertThat(store.getRecentMovieIds(1, 10)).containsExactly(10);
        when(stub.cmd.get("user:1:recent_movies")).thenReturn("20");

        assertThat(store.getRecentMovieIdsPrimary(1, 10)).containsExactly(20);

        verify(stub.exec).executePrimaryRead(any());
        verify(stub.exec).executeRead(any());
    }

    @Test
    void getRecentMovieIds_cachesRedisResultWithinTtl() throws Exception {
        var stub = RedisExecutorStub.withHistory(1, "10 20 30");
        var store = new OnlineFeatureStore(stub.exec, 5_000L);

        List<Integer> first  = store.getRecentMovieIds(1, 10);
        List<Integer> second = store.getRecentMovieIds(1, 10);

        assertThat(first).containsExactly(10, 20, 30);
        assertThat(second).isEqualTo(first);
        // Redis was only touched once (second call is a cache hit)
        verify(stub.cmd, times(1)).get("user:1:recent_movies");
    }

    @Test
    void getRecentMovieIds_refetchesAfterTtlExpiry() throws Exception {
        var stub = RedisExecutorStub.withHistory(1, "10 20 30");
        var store = new OnlineFeatureStore(stub.exec, 1L); // 1 ms TTL

        store.getRecentMovieIds(1, 10);
        Thread.sleep(5);
        store.getRecentMovieIds(1, 10);

        verify(stub.cmd, times(2)).get("user:1:recent_movies");
    }

    @Test
    void getRecentMovieIds_servesBoundedStaleValueWhenRedisFails() throws Exception {
        var stub = new RedisExecutorStub();
        when(stub.cmd.get("user:1:recent_movies"))
                .thenReturn("10 20")
                .thenThrow(new IllegalStateException("redis down"));
        var store = new OnlineFeatureStore(stub.exec, 1L, 5_000L, 100);

        assertThat(store.getRecentMovieIds(1, 10)).containsExactly(10, 20);
        Thread.sleep(5);

        assertThat(store.getRecentMovieIds(1, 10)).containsExactly(10, 20);
        verify(stub.cmd, times(2)).get("user:1:recent_movies");
    }

    @Test
    void getRecentMovieIds_appliesLimitFromCache() {
        var stub = RedisExecutorStub.withHistory(2, "5 6 7 8 9");
        var store = new OnlineFeatureStore(stub.exec, 5_000L);

        List<Integer> result = store.getRecentMovieIds(2, 3);

        // limit returns the LAST k entries (most recent)
        assertThat(result).hasSize(3).containsExactly(7, 8, 9);
    }

    @Test
    void getRecentMovieIds_emptyRedisValue_returnsEmpty() {
        var stub = RedisExecutorStub.withHistory(3, null);
        var store = new OnlineFeatureStore(stub.exec, 5_000L);

        assertThat(store.getRecentMovieIds(3, 5)).isEmpty();
    }

    @Test
    void getRecentMovieIds_malformedTokensIgnored() {
        var stub = RedisExecutorStub.withHistory(4, "1 bad 3");
        var store = new OnlineFeatureStore(stub.exec, 5_000L);

        assertThat(store.getRecentMovieIds(4, 10)).containsExactly(1, 3);
    }

    @Test
    void getRecentMovieIds_evictsWhenHotUserCacheExceedsLimit() {
        var stub = new RedisExecutorStub();
        when(stub.cmd.get("user:1:recent_movies")).thenReturn("1");
        when(stub.cmd.get("user:2:recent_movies")).thenReturn("2");
        when(stub.cmd.get("user:3:recent_movies")).thenReturn("3");
        var store = new OnlineFeatureStore(stub.exec, 5_000L, 2);

        store.getRecentMovieIds(1, 10);
        store.getRecentMovieIds(2, 10);
        store.getRecentMovieIds(3, 10);

        assertThat(store.cacheSize()).isLessThanOrEqualTo(2);
    }

    @Test
    void getFeature_cachesNullSentinelWithinTtl() {
        var stub = new RedisExecutorStub();
        when(stub.cmd.get("user:9:embedding")).thenReturn(null);
        var store = new OnlineFeatureStore(stub.exec, 5_000L);

        assertThat(store.getFeature("user:9:embedding")).isNull();
        assertThat(store.getFeature("user:9:embedding")).isNull();

        verify(stub.cmd, times(1)).get("user:9:embedding");
    }

    @Test
    void getFeatures_deduplicatesChunksAndCachesRedisMget() {
        var stub = new RedisExecutorStub();
        when(stub.cmd.mget("user:1:embedding", "movie:7:engagement"))
                .thenReturn(kvs(List.of("user:1:embedding", "movie:7:engagement"), List.of("0.1 0.2", "0.42")));
        when(stub.cmd.mget("session:abc:features"))
                .thenReturn(kvs(List.of("session:abc:features"), List.of("fresh")));
        var store = new OnlineFeatureStore(stub.exec, 5_000L, 100, 2);

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
        verify(stub.cmd, times(1)).mget("user:1:embedding", "movie:7:engagement");
        verify(stub.cmd, times(1)).mget("session:abc:features");
    }

    @Test
    void getFeatures_cachesMissingFeatureKeys() {
        var stub = new RedisExecutorStub();
        when(stub.cmd.mget("trend:hourly"))
                .thenReturn(List.of(KeyValue.empty("trend:hourly")));
        var store = new OnlineFeatureStore(stub.exec, 5_000L, 100, 10);

        assertThat(store.getFeatures(List.of("trend:hourly"))).isEmpty();
        assertThat(store.getFeatures(List.of("trend:hourly"))).isEmpty();

        verify(stub.cmd, times(1)).mget("trend:hourly");
    }

    @Test
    void getFeatures_servesStaleValueWhenRedisFailsOnBatchPath() throws Exception {
        var stub = new RedisExecutorStub();
        // First call succeeds and populates cache
        when(stub.cmd.mget("user:1:embedding"))
                .thenReturn(kvs(List.of("user:1:embedding"), List.of("0.1 0.2")));
        var store = new OnlineFeatureStore(stub.exec, 1L, 5_000L, 100, 10);

        // Warm the cache
        Map<String, String> first = store.getFeatures(List.of("user:1:embedding"));
        assertThat(first).containsEntry("user:1:embedding", "0.1 0.2");

        // Redis goes down after TTL expires
        Thread.sleep(5);
        when(stub.cmd.mget("user:1:embedding"))
                .thenThrow(new RuntimeException("redis down"));

        // Should serve stale value within staleTtlMs (5000 ms), not throw
        Map<String, String> stale = store.getFeatures(List.of("user:1:embedding"));
        assertThat(stale).containsEntry("user:1:embedding", "0.1 0.2");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static List<KeyValue<String, String>> kvs(List<String> keys, List<String> values) {
        List<KeyValue<String, String>> out = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String v = values.get(i);
            out.add(v == null ? KeyValue.empty(keys.get(i)) : KeyValue.just(keys.get(i), v));
        }
        return out;
    }

    // ── minimal RedisExecutor / RedisCommands stub ──────────────────────────

    private static final class RedisExecutorStub {
        final RedisExecutor exec = mock(RedisExecutor.class);
        @SuppressWarnings("unchecked")
        final RedisCommands<String, String> cmd = mock(RedisCommands.class);

        @SuppressWarnings("unchecked")
        RedisExecutorStub() {
            when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
            when(exec.executeRead(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
            when(exec.executePrimaryRead(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        }

        static RedisExecutorStub withHistory(int userId, String value) {
            var stub = new RedisExecutorStub();
            when(stub.cmd.get("user:" + userId + ":recent_movies")).thenReturn(value);
            return stub;
        }
    }
}
