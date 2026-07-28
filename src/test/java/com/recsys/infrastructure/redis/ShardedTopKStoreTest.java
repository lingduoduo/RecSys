package com.recsys.infrastructure.redis;

import com.recsys.infrastructure.resilience.HotKeyDetector;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShardedTopKStoreTest {

    private RedisCommands<String, String> cmd;
    private RedisExecutor exec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cmd = mock(RedisCommands.class);
        exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        when(exec.executeRead(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        when(exec.executePrimaryRead(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
    }

    @Test
    void primaryReadBypassesHotCacheAndReplicaReadPath() {
        when(cmd.eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("canonical", "fresh", "2"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIdsPrimary("last_hour", 2)).containsExactly("fresh", "2");

        verify(exec).executePrimaryRead(any());
        verify(exec, never()).executeRead(any());
    }

    @Test
    void canonicalMarkerBeatsPopulatedLegacyKey() {
        when(cmd.eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("canonical", "fresh"));
        when(cmd.zrevrange(eq("topk:last_hour"), anyLong(), anyLong()))
                .thenReturn(List.of("legacy-stale"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 0L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 1)).containsExactly("fresh");
        verify(cmd, never()).zrevrange(eq("topk:last_hour"), anyLong(), anyLong());
    }

    @Test
    void canonicalMarkerMakesEmptySnapshotAuthoritativeAndCacheable() {
        when(cmd.eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("canonical"));
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong())).thenReturn(List.of("stale"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 1)).isEmpty();
        assertThat(store.getTopKIds("last_hour", 1)).isEmpty();

        verify(exec, times(1)).executeRead(any());
        verify(cmd, never()).zrevrange(any(String.class), anyLong(), anyLong());
    }

    @Test
    void absentCanonicalMarkerFallsBackToLegacyKey() {
        when(cmd.eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("absent"));
        when(cmd.zrevrange("topk:last_hour", 0, 99)).thenReturn(List.of("legacy"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 0L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 1)).containsExactly("legacy");
        assertThat(store.legacyFallbackFetches()).isEqualTo(1L);
    }

    @Test
    void readPathNeverTouchesAShardKey() {
        when(cmd.eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("absent"));
        when(cmd.zrevrange("topk:last_hour", 0, 99)).thenReturn(List.of("legacy"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 0L, new HotKeyDetector());

        for (int i = 0; i < 20; i++) store.getTopKIds("last_hour", 1);

        // The sharded keyspace is gone: no read may address topk:<window>:sN.
        verify(cmd, never()).zrevrange(
                argThat((String key) -> key.matches("topk:last_hour:s\\d+")), anyLong(), anyLong());
    }

    @Test
    void primaryReadHonorsEmptyCanonicalSnapshotWithoutLegacyFallback() {
        when(cmd.eval(any(String.class), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("canonical"));
        when(cmd.zrevrange("topk:last_hour", 0, 1)).thenReturn(List.of("legacy-stale"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIdsPrimary("last_hour", 2)).isEmpty();
        verify(exec).executePrimaryRead(any());
        verify(exec, never()).executeRead(any());
        verify(cmd, never()).zrevrange("topk:last_hour", 0, 1);
    }

    // ── Read path: local JVM cache ────────────────────────────────────────────────

    @Test
    void getTopKIds_servesFromLocalCacheWithinTtl() {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("1", "2", "3"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 5_000L, new HotKeyDetector());

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
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 1L, new HotKeyDetector());

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
                exec, exec, "topk:", 1L, 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 2)).containsExactly("1", "2");
        Thread.sleep(5);

        assertThat(store.getTopKIds("last_hour", 2)).containsExactly("1", "2");
    }

    @Test
    void getTopKIds_slicesResultToRequestedK() {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("1", "2", "3", "4", "5"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 5_000L, new HotKeyDetector());

        assertThat(store.getTopKIds("last_hour", 3)).containsExactly("1", "2", "3");
        assertThat(store.getTopKIds("last_hour", 5)).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void getTopKIds_returnsEmptyForNonPositiveK() {
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 5_000L, new HotKeyDetector());
        assertThat(store.getTopKIds("last_hour", 0)).isEmpty();
        assertThat(store.getTopKIds("last_hour", -1)).isEmpty();
        verify(exec, never()).executeRead(any());
    }

    // ── Metrics ───────────────────────────────────────────────────────────────────

    @Test
    void localHitRate_isZeroOnColdStart() {
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 5_000L, new HotKeyDetector());
        assertThat(store.localHitRate()).isEqualTo(0.0);
    }

    @Test
    void localHitRate_improvesAfterFirstCacheFill() {
        when(cmd.zrevrange(any(String.class), anyLong(), anyLong()))
                .thenReturn(List.of("1"));
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 5_000L, new HotKeyDetector());

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
        ShardedTopKStore store = new ShardedTopKStore(exec, exec, "topk:", 0L, detector);

        for (int i = 0; i < 100; i++) store.getTopKIds("last_hour", 1);

        assertThat(detector.accessRate("last_hour")).isGreaterThan(0.0);
        assertThat(detector.isHot("last_hour")).isTrue();
    }
}
