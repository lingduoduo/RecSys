package com.recsys.infrastructure.redis;

import com.recsys.infrastructure.resilience.HotKeyDetector;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShardedTopKStoreTest {

    private RedisCommands<String, String> cmd;
    private RedisExecutor exec;
    private RedisAsyncCommands<String, String> async;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cmd = mock(RedisCommands.class);
        exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        when(exec.executeRead(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        when(exec.executePrimaryRead(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));

        // Pipeline wiring: executePipelined runs the consumer against a mocked connection.
        StatefulRedisConnection<String, String> conn = mock(StatefulRedisConnection.class);
        async = mock(RedisAsyncCommands.class);
        when(conn.async()).thenReturn(async);
        RedisFuture<Long> future = mock(RedisFuture.class);
        when(async.zadd(any(String.class), any(ScoredValue[].class)))
                .thenReturn(future);
        doAnswerPipeline(conn);
    }

    @Test
    void primaryReadBypassesHotCacheAndReplicaReadPath() {
        when(cmd.zrevrange("topk:last_hour", 0, 1)).thenReturn(List.of("fresh", "2"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 2, 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIdsPrimary("last_hour", 2)).containsExactly("fresh", "2");

        verify(exec).executePrimaryRead(any());
        verify(exec, never()).executeRead(any());
    }

    @SuppressWarnings("unchecked")
    private void doAnswerPipeline(StatefulRedisConnection<String, String> conn) {
        org.mockito.Mockito.doAnswer(i -> {
            i.getArgument(0, Consumer.class).accept(conn);
            return null;
        }).when(exec).executePipelined(any());
    }

    // ── Key-shard naming ──────────────────────────────────────────────────────────

    @Test
    void shardKey_producesExpectedPattern() {
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 4, 5_000L, new HotKeyDetector());
        assertThat(store.shardKey("last_hour", 0)).isEqualTo("topk:last_hour:s0");
        assertThat(store.shardKey("last_hour", 3)).isEqualTo("topk:last_hour:s3");
    }

    // ── Read path: local JVM cache ────────────────────────────────────────────────

    @Test
    void getTopKIds_servesFromLocalCacheWithinTtl() {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("1", "2", "3"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 2, 5_000L, new HotKeyDetector());

        store.getTopKIds("last_hour", 3); // cold fetch → Redis
        store.getTopKIds("last_hour", 3); // warm hit → local cache

        // Only one Redis call despite two invocations.
        verify(cmd, times(1)).zrevrange(any(String.class), eq(0L), eq(99L));
        assertThat(store.localHits()).isEqualTo(1L);
        assertThat(store.redisFetches()).isEqualTo(1L);
    }

    @Test
    void getTopKIds_refetchesAfterCacheTtlExpires() throws InterruptedException {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("1", "2"));
        // 1 ms TTL expires immediately.
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 2, 1L, new HotKeyDetector());

        store.getTopKIds("last_hour", 2);
        Thread.sleep(5);
        store.getTopKIds("last_hour", 2);

        verify(cmd, times(2)).zrevrange(any(String.class), eq(0L), eq(99L));
    }

    @Test
    void getTopKIds_servesBoundedStaleValueWhenRedisFails() throws InterruptedException {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("1", "2"))
                .thenThrow(new IllegalStateException("redis down"));
        ShardedTopKStore store = new ShardedTopKStore(
                exec, exec, "topk:", 2, 1L, 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 2)).containsExactly("1", "2");
        Thread.sleep(5);

        assertThat(store.getTopKIds("last_hour", 2)).containsExactly("1", "2");
    }

    @Test
    void getTopKIds_slicesResultToRequestedK() {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("1", "2", "3", "4", "5"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 1, 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 3)).containsExactly("1", "2", "3");
        assertThat(store.getTopKIds("last_hour", 5)).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void getTopKIds_returnsEmptyForNonPositiveK() {
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 2, 5_000L, new HotKeyDetector());
        assertThat(store.getTopKIds("last_hour", 0)).isEmpty();
        assertThat(store.getTopKIds("last_hour", -1)).isEmpty();
        verify(exec, never()).executeRead(any());
    }

    // ── Read path: shard selection ────────────────────────────────────────────────

    @Test
    void getTopKIds_readsFromOneOfNShards() {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("10", "20"));
        // 4 shards; cache TTL = 0 so every call hits Redis.
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 4, 0L, new HotKeyDetector());

        for (int i = 0; i < 20; i++) store.getTopKIds("last_hour", 2);

        // Every call must read from a key that matches one of the 4 shard patterns.
        verify(cmd, atLeast(1)).zrevrange(
                argThat((String key) -> key.matches("topk:last_hour:s[0-3]")), anyLong(), anyLong());
    }

    @Test
    void getTopKIds_fallsBackToLegacyKeyWhenShardIsEmpty() {
        when(cmd.zrevrange(argThat((String key) -> key.matches("topk:last_hour:s[0-3]")), anyLong(), anyLong()))
                .thenReturn(List.of());
        when(cmd.zrevrange("topk:last_hour", 0, 99))
                .thenReturn(List.of("10", "20"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 4, 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 2)).containsExactly("10", "20");
        assertThat(store.legacyFallbackFetches()).isEqualTo(1L);
    }

    // ── Write path: fan-out to all shards ─────────────────────────────────────────

    @Test
    void seedAllShards_writesToEveryShardKey() {
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 3, 5_000L, new HotKeyDetector());
        Map<String, Double> scores = Map.of("movie:1", 10.0, "movie:2", 8.0);

        store.seedAllShards("last_hour", scores);

        ArgumentCaptor<ScoredValue<String>[]> captor = ArgumentCaptor.forClass(ScoredValue[].class);
        verify(async).zadd(eq("topk:last_hour:s0"), captor.capture());
        verify(async).zadd(eq("topk:last_hour:s1"), captor.capture());
        verify(async).zadd(eq("topk:last_hour:s2"), captor.capture());
        verify(async).zadd(eq("topk:last_hour"), captor.capture());

        // Each ZADD carried both members with their scores (order is map-dependent).
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(
                        ScoredValue.just(10.0, "movie:1"),
                        ScoredValue.just(8.0, "movie:2"));
    }

    @Test
    void seedAllShards_invalidatesLocalCache() {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("old"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 2, 60_000L, new HotKeyDetector());
        store.getTopKIds("last_hour", 1); // populates local cache

        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("new"));
        store.seedAllShards("last_hour", Map.of("new", 1.0));

        List<String> result = store.getTopKIds("last_hour", 1);
        assertThat(result).containsExactly("new"); // stale cache was cleared
    }

    @Test
    void seedAllShards_noopsForNullOrEmptyScores() {
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 2, 5_000L, new HotKeyDetector());
        store.seedAllShards("last_hour", null);
        store.seedAllShards("last_hour", Map.of());
        verify(exec, never()).executePipelined(any());
    }

    // ── Metrics ───────────────────────────────────────────────────────────────────

    @Test
    void localHitRate_isZeroOnColdStart() {
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 2, 5_000L, new HotKeyDetector());
        assertThat(store.localHitRate()).isEqualTo(0.0);
    }

    @Test
    void localHitRate_improvesAfterFirstCacheFill() {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("1"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 1, 5_000L, new HotKeyDetector());

        store.getTopKIds("last_hour", 1); // Redis fetch
        store.getTopKIds("last_hour", 1); // local hit
        store.getTopKIds("last_hour", 1); // local hit

        assertThat(store.localHitRate()).isEqualTo(2.0 / 3.0);
    }

    // ── HotKeyDetector integration ────────────────────────────────────────────────

    @Test
    void getTopKIds_recordsWindowAccessInDetector() {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("1"));
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 1, 0L, detector);

        for (int i = 0; i < 100; i++) store.getTopKIds("last_hour", 1);

        assertThat(detector.accessRate("last_hour")).isGreaterThan(0.0);
        assertThat(detector.isHot("last_hour")).isTrue();
    }
}
