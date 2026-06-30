package com.recsys.infrastructure.redis;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalPopularityStoreTest {

    @SuppressWarnings("unchecked")
    private static RedisExecutor execFor(RedisCommands<String, String> cmd) {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        when(exec.executeRead(any())).thenAnswer(i -> i.getArgument(0, Function.class).apply(cmd));
        return exec;
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> mockCmd() {
        return mock(RedisCommands.class);
    }

    @Test
    void getTopIds_returnsIdsFromRedisSortedSetInOrder() {
        RedisCommands<String, String> cmd = mockCmd();
        when(cmd.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "3", "1"));

        GlobalPopularityStore store = new GlobalPopularityStore(execFor(cmd));
        List<String> ids = store.getTopIds(3);

        assertThat(ids).containsExactly("5", "3", "1");
        verify(cmd).zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong());
    }

    @Test
    void getTopIds_emptyWhenRedisKeyMissing() {
        RedisCommands<String, String> cmd = mockCmd();
        when(cmd.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of());

        List<String> ids = new GlobalPopularityStore(execFor(cmd)).getTopIds(10);

        assertThat(ids).isEmpty();
    }

    @Test
    void getTopIds_zeroLimitReturnsEmpty() {
        RedisCommands<String, String> cmd = mockCmd();
        List<String> ids = new GlobalPopularityStore(execFor(cmd)).getTopIds(0);
        assertThat(ids).isEmpty();
    }

    @Test
    void getTopIds_cachesWithinFreshTtl_singleRedisRead() {
        RedisCommands<String, String> cmd = mockCmd();
        when(cmd.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "3", "1"));

        AtomicLong clock = new AtomicLong(0);
        GlobalPopularityStore store =
                new GlobalPopularityStore(execFor(cmd), 1_000L, 60_000L, clock::get);

        assertThat(store.getTopIds(3)).containsExactly("5", "3", "1");
        assertThat(store.getTopIds(3)).containsExactly("5", "3", "1"); // within TTL

        verify(cmd, times(1)).zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong());
    }

    @Test
    void getTopIds_slicesTopNSnapshotByLimit_oneRedisRead() {
        RedisCommands<String, String> cmd = mockCmd();
        when(cmd.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "4", "3", "2", "1"));

        AtomicLong clock = new AtomicLong(0);
        GlobalPopularityStore store =
                new GlobalPopularityStore(execFor(cmd), 1_000L, 60_000L, clock::get);

        assertThat(store.getTopIds(2)).containsExactly("5", "4");
        assertThat(store.getTopIds(4)).containsExactly("5", "4", "3", "2"); // shared snapshot

        verify(cmd, times(1)).zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong());
    }

    @Test
    void getTopIds_servesStaleOnRedisErrorThenEmptyBeyondStale() {
        RedisCommands<String, String> cmd = mockCmd();
        when(cmd.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenReturn(List.of("5", "3"))                 // first load OK
                .thenThrow(new RedisException("down"));         // subsequent loads fail

        AtomicLong clock = new AtomicLong(0);
        GlobalPopularityStore store =
                new GlobalPopularityStore(execFor(cmd), 10L, 100L, clock::get);

        assertThat(store.getTopIds(2)).containsExactly("5", "3"); // t=0 seed

        clock.set(50);                                            // stale window
        assertThat(store.getTopIds(2)).containsExactly("5", "3"); // served stale

        clock.set(200);                                           // beyond stale
        assertThat(store.getTopIds(2)).isEmpty();                 // error → empty (DataManager fallback upstream)
    }

    @Test
    void getTopIds_returnsEmptyWhenRedisDownAndNoSnapshot() {
        RedisCommands<String, String> cmd = mockCmd();
        when(cmd.zrevrange(eq(GlobalPopularityStore.KEY), anyLong(), anyLong()))
                .thenThrow(new RedisException("down"));

        GlobalPopularityStore store = new GlobalPopularityStore(execFor(cmd));
        assertThat(store.getTopIds(5)).isEmpty();
    }
}
