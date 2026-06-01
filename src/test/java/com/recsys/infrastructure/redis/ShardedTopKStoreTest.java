package com.recsys.infrastructure.redis;

import com.recsys.infrastructure.HotKeyDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShardedTopKStoreTest {

    private Jedis jedis;
    private JedisPool pool;

    @BeforeEach
    void setUp() {
        jedis = mock(Jedis.class);
        pool  = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
    }

    // ── Key-shard naming ──────────────────────────────────────────────────────────

    @Test
    void shardKey_producesExpectedPattern() {
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 4, 5_000L, new HotKeyDetector());
        assertThat(store.shardKey("last_hour", 0)).isEqualTo("topk:last_hour:s0");
        assertThat(store.shardKey("last_hour", 3)).isEqualTo("topk:last_hour:s3");
    }

    // ── Read path: local JVM cache ────────────────────────────────────────────────

    @Test
    void getTopKIds_servesFromLocalCacheWithinTtl() {
        when(jedis.zrevrange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of("1", "2", "3"));
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 2, 5_000L, new HotKeyDetector());

        store.getTopKIds("last_hour", 3); // cold fetch → Redis
        store.getTopKIds("last_hour", 3); // warm hit → local cache

        // Only one Redis call despite two invocations.
        verify(jedis, times(1)).zrevrange(anyString(), eq(0L), eq(99L));
        assertThat(store.localHits()).isEqualTo(1L);
        assertThat(store.redisFetches()).isEqualTo(1L);
    }

    @Test
    void getTopKIds_refetchesAfterCacheTtlExpires() throws InterruptedException {
        when(jedis.zrevrange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of("1", "2"));
        // 1 ms TTL expires immediately.
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 2, 1L, new HotKeyDetector());

        store.getTopKIds("last_hour", 2);
        Thread.sleep(5);
        store.getTopKIds("last_hour", 2);

        verify(jedis, times(2)).zrevrange(anyString(), eq(0L), eq(99L));
    }

    @Test
    void getTopKIds_slicesResultToRequestedK() {
        when(jedis.zrevrange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of("1", "2", "3", "4", "5"));
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 1, 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 3)).containsExactly("1", "2", "3");
        assertThat(store.getTopKIds("last_hour", 5)).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void getTopKIds_returnsEmptyForNonPositiveK() {
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 2, 5_000L, new HotKeyDetector());
        assertThat(store.getTopKIds("last_hour", 0)).isEmpty();
        assertThat(store.getTopKIds("last_hour", -1)).isEmpty();
        verifyNoInteractions(jedis);
    }

    // ── Read path: shard selection ────────────────────────────────────────────────

    @Test
    void getTopKIds_readsFromOneOfNShards() {
        when(jedis.zrevrange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of("10", "20"));
        // 4 shards; cache TTL = 0 so every call hits Redis.
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 4, 0L, new HotKeyDetector());

        for (int i = 0; i < 20; i++) store.getTopKIds("last_hour", 2);

        // Every call must read from a key that matches one of the 4 shard patterns.
        verify(jedis, atLeast(1)).zrevrange(
                argThat((String key) -> key.matches("topk:last_hour:s[0-3]")), anyLong(), anyLong());
    }

    @Test
    void getTopKIds_fallsBackToLegacyKeyWhenShardIsEmpty() {
        when(jedis.zrevrange(argThat((String key) -> key.matches("topk:last_hour:s[0-3]")), anyLong(), anyLong()))
                .thenReturn(List.of());
        when(jedis.zrevrange("topk:last_hour", 0, 99))
                .thenReturn(List.of("10", "20"));
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 4, 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 2)).containsExactly("10", "20");
        assertThat(store.legacyFallbackFetches()).isEqualTo(1L);
    }

    // ── Write path: fan-out to all shards ─────────────────────────────────────────

    @Test
    void seedAllShards_writesToEveryShardKey() {
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 3, 5_000L, new HotKeyDetector());
        Map<String, Double> scores = Map.of("movie:1", 10.0, "movie:2", 8.0);

        store.seedAllShards("last_hour", scores);

        verify(jedis).zadd("topk:last_hour:s0", scores);
        verify(jedis).zadd("topk:last_hour:s1", scores);
        verify(jedis).zadd("topk:last_hour:s2", scores);
        verify(jedis).zadd("topk:last_hour", scores);
    }

    @Test
    void seedAllShards_invalidatesLocalCache() {
        when(jedis.zrevrange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of("old"));
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 2, 60_000L, new HotKeyDetector());
        store.getTopKIds("last_hour", 1); // populates local cache

        when(jedis.zrevrange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of("new"));
        store.seedAllShards("last_hour", Map.of("new", 1.0));

        List<String> result = store.getTopKIds("last_hour", 1);
        assertThat(result).containsExactly("new"); // stale cache was cleared
    }

    @Test
    void seedAllShards_noopsForNullOrEmptyScores() {
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 2, 5_000L, new HotKeyDetector());
        store.seedAllShards("last_hour", null);
        store.seedAllShards("last_hour", Map.of());
        verifyNoInteractions(jedis);
    }

    // ── Metrics ───────────────────────────────────────────────────────────────────

    @Test
    void localHitRate_isZeroOnColdStart() {
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 2, 5_000L, new HotKeyDetector());
        assertThat(store.localHitRate()).isEqualTo(0.0);
    }

    @Test
    void localHitRate_improvesAfterFirstCacheFill() {
        when(jedis.zrevrange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of("1"));
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 1, 5_000L, new HotKeyDetector());

        store.getTopKIds("last_hour", 1); // Redis fetch
        store.getTopKIds("last_hour", 1); // local hit
        store.getTopKIds("last_hour", 1); // local hit

        assertThat(store.localHitRate()).isEqualTo(2.0 / 3.0);
    }

    // ── HotKeyDetector integration ────────────────────────────────────────────────

    @Test
    void getTopKIds_recordsWindowAccessInDetector() {
        when(jedis.zrevrange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of("1"));
        HotKeyDetector detector = new HotKeyDetector(10, 1L);
        ShardedTopKStore store = new ShardedTopKStore(pool, "topk:", 1, 0L, detector);

        for (int i = 0; i < 100; i++) store.getTopKIds("last_hour", 1);

        assertThat(detector.accessRate("last_hour")).isGreaterThan(0.0);
        assertThat(detector.isHot("last_hour")).isTrue();
    }
}
