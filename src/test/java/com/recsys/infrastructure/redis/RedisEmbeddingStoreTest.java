package com.recsys.infrastructure.redis;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisEmbeddingStoreTest {

    @SuppressWarnings("unchecked")
    private static RedisExecutor execFor(RedisCommands<String, String> cmd) {
        RedisExecutor exec = mock(RedisExecutor.class);
        when(exec.execute(any())).thenAnswer(i ->
                i.getArgument(0, Function.class).apply(cmd));
        when(exec.executeRead(any())).thenAnswer(i ->
                i.getArgument(0, Function.class).apply(cmd));
        return exec;
    }

    private static List<KeyValue<String, String>> kvs(String... values) {
        java.util.List<KeyValue<String, String>> list = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            list.add(values[i] == null
                    ? KeyValue.empty("k" + i)
                    : KeyValue.just("k" + i, values[i]));
        }
        return list;
    }

    @Test
    void jitteredTtlMillis_neverShortensBaseTtlAndCapsJitter() {
        RedisEmbeddingStore store = new RedisEmbeddingStore((RedisExecutor) null, "emb", 0.10);
        long baseMs = 60_000L;

        for (int i = 0; i < 200; i++) {
            long ttlMs = store.jitteredTtlMillis(60L);
            assertThat(ttlMs).isBetween(baseMs, 66_000L);
        }
    }

    @Test
    void jitteredTtlMillis_returnsExactBaseWhenJitterDisabled() {
        RedisEmbeddingStore store = new RedisEmbeddingStore((RedisExecutor) null, "emb", 0.0);

        assertThat(store.jitteredTtlMillis(30L)).isEqualTo(30_000L);
    }

    @Test
    void jitteredTtlMillis_preservesNonExpiringTtlSentinel() {
        RedisEmbeddingStore store = new RedisEmbeddingStore((RedisExecutor) null, "emb", 0.10);

        assertThat(store.jitteredTtlMillis(0L)).isZero();
        assertThat(store.jitteredTtlMillis(-1L)).isEqualTo(-1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getEmbeddings_deduplicatesAndChunksRedisMget() {
        RedisCommands<String, String> cmd = mock(RedisCommands.class);
        when(cmd.mget("emb:1", "emb:2")).thenReturn(kvs("1.0 0.0", "0.0 1.0"));
        when(cmd.mget("emb:3")).thenReturn(kvs("0.5 0.5"));
        RedisExecutor exec = execFor(cmd);

        RedisEmbeddingStore store = new RedisEmbeddingStore(exec, "emb", 0.0, 2);

        Map<Integer, float[]> result = store.getEmbeddings(List.of(1, 1, 2, 3));

        assertThat(result).containsOnlyKeys(1, 2, 3);
        assertThat(result.get(1)).containsExactly(1.0f, 0.0f);
        assertThat(result.get(2)).containsExactly(0.0f, 1.0f);
        assertThat(result.get(3)).containsExactly(0.5f, 0.5f);
        verify(cmd).mget("emb:1", "emb:2");
        verify(cmd).mget("emb:3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadAll_stopsWhenTimeBudgetExceeded() {
        RedisCommands<String, String> cmd = mock(RedisCommands.class);
        RedisExecutor exec = execFor(cmd);

        AtomicLong now = new AtomicLong(0L);
        LongSupplier clock = () -> now.getAndAdd(1000L); // each read advances 1s
        RedisEmbeddingStore store =
                new RedisEmbeddingStore(exec, "emb", 0.0, 500, 500L, clock); // 500ms budget

        KeyScanCursor<String> page = mock(KeyScanCursor.class);
        when(page.getKeys()).thenReturn(List.of("emb:1"));
        when(page.isFinished()).thenReturn(false); // never finished -> would loop forever without the budget
        when(cmd.scan(any(ScanArgs.class))).thenReturn(page);
        when(cmd.scan(any(KeyScanCursor.class), any(ScanArgs.class))).thenReturn(page);
        when(cmd.mget(any(String[].class))).thenReturn(kvs("1.0 0.0"));

        Map<Integer, float[]> result = store.loadAll();

        assertThat(result).containsKey(1);                                   // partial result returned
        // budget broke the loop: at most the initial scan + one continuation
        verify(cmd, atMost(1)).scan(any(ScanArgs.class));
        verify(cmd, atMost(1)).scan(any(KeyScanCursor.class), any(ScanArgs.class));
    }
}
