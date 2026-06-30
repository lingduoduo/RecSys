package com.recsys.infrastructure.redis;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTopKStoreTest {

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
    void getTopKIds_cachesFullWindowListWithinTtl() {
        RedisCommands<String, String> cmd = mockCmd();
        // Store now fetches up to MAX_FULL_CACHE_SIZE=100 items per window
        when(cmd.zrevrange("topk:last_hour", 0, 99)).thenReturn(List.of("1", "2", "3"));
        RedisTopKStore store = new RedisTopKStore(execFor(cmd), "topk:", 5_000L);

        assertThat(store.getTopKIds("last_hour", 5)).containsExactly("1", "2", "3");
        assertThat(store.getTopKIds("last_hour", 5)).containsExactly("1", "2", "3");

        // Only one Redis fetch regardless of how many calls share the same window
        verify(cmd, times(1)).zrevrange("topk:last_hour", 0, 99);
        assertThat(store.hotCacheSize()).isEqualTo(1);
    }

    @Test
    void getTopKIds_differentKValuesShareOneCacheEntry() {
        RedisCommands<String, String> cmd = mockCmd();
        when(cmd.zrevrange("topk:last_hour", 0, 99))
                .thenReturn(List.of("1", "2", "3", "4", "5"));
        RedisTopKStore store = new RedisTopKStore(execFor(cmd), "topk:", 5_000L);

        List<String> k2 = store.getTopKIds("last_hour", 2);
        List<String> k4 = store.getTopKIds("last_hour", 4);

        assertThat(k2).containsExactly("1", "2");
        assertThat(k4).containsExactly("1", "2", "3", "4");
        // Both k values share one Redis round-trip and one cache entry
        verify(cmd, times(1)).zrevrange("topk:last_hour", 0, 99);
        assertThat(store.hotCacheSize()).isEqualTo(1);
    }

    @Test
    void getTopKIds_canDisableLocalCache() {
        RedisCommands<String, String> cmd = mockCmd();
        when(cmd.zrevrange("topk:last_day", 0, 99)).thenReturn(List.of("5", "6"));
        RedisTopKStore store = new RedisTopKStore(execFor(cmd), "topk:", 0L);

        store.getTopKIds("last_day", 2);
        store.getTopKIds("last_day", 2);

        verify(cmd, times(2)).zrevrange("topk:last_day", 0, 99);
        assertThat(store.hotCacheSize()).isZero();
    }

    @Test
    void getTopKIds_nonPositiveK_returnsEmptyWithoutRedisCall() {
        RedisCommands<String, String> cmd = mockCmd();
        RedisExecutor exec = execFor(cmd);
        RedisTopKStore store = new RedisTopKStore(exec, "topk:", 5_000L);

        assertThat(store.getTopKIds("last_hour", 0)).isEmpty();

        verify(exec, never()).executeRead(any());
        verify(exec, never()).execute(any());
    }

    @Test
    void getTopKIds_deduplicatesConcurrentMissesForSameWindow() throws Exception {
        RedisCommands<String, String> cmd = mockCmd();
        CountDownLatch redisEntered = new CountDownLatch(1);
        CountDownLatch releaseRedis = new CountDownLatch(1);
        when(cmd.zrevrange("topk:last_hour", 0, 99)).thenAnswer(invocation -> {
            redisEntered.countDown();
            assertThat(releaseRedis.await(1, TimeUnit.SECONDS)).isTrue();
            return List.of("1", "2", "3");
        });
        RedisTopKStore store = new RedisTopKStore(execFor(cmd), "topk:", 5_000L);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> store.getTopKIds("last_hour", 2));
            assertThat(redisEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> store.getTopKIds("last_hour", 2));

            releaseRedis.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS)).containsExactly("1", "2");
            assertThat(second.get(1, TimeUnit.SECONDS)).containsExactly("1", "2");
            verify(cmd, times(1)).zrevrange("topk:last_hour", 0, 99);
        } finally {
            releaseRedis.countDown();
            executor.shutdownNow();
        }
    }
}
