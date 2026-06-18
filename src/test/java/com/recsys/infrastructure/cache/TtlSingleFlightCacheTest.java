package com.recsys.infrastructure.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtlSingleFlightCacheTest {

    @Test
    void freshHit_callsLoaderOnce() {
        AtomicLong clock = new AtomicLong(0);
        TtlSingleFlightCache<String> cache =
                new TtlSingleFlightCache<>(1_000L, 60_000L, clock::get);
        AtomicInteger loads = new AtomicInteger();

        String first = cache.get("k", () -> { loads.incrementAndGet(); return "A"; });
        String second = cache.get("k", () -> { loads.incrementAndGet(); return "A"; });

        assertThat(first).isEqualTo("A");
        assertThat(second).isEqualTo("A");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void distinctKeys_areIsolated() {
        AtomicLong clock = new AtomicLong(0);
        TtlSingleFlightCache<String> cache =
                new TtlSingleFlightCache<>(1_000L, 60_000L, clock::get);

        assertThat(cache.get("a", () -> "VA")).isEqualTo("VA");
        assertThat(cache.get("b", () -> "VB")).isEqualTo("VB");
        assertThat(cache.get("a", () -> "X")).isEqualTo("VA"); // still cached, loader ignored
    }

    @Test
    void coldMiss_propagatesLoaderException() {
        TtlSingleFlightCache<String> cache = new TtlSingleFlightCache<>(1_000L, 60_000L);

        assertThatThrownBy(() -> cache.get("k", () -> { throw new IllegalStateException("down"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("down");
    }

    @Test
    void staleWindow_servesStaleOnLoaderError_thenPropagatesBeyondStale() {
        AtomicLong clock = new AtomicLong(0);
        TtlSingleFlightCache<String> cache =
                new TtlSingleFlightCache<>(10L, 100L, clock::get); // fresh 10ms, stale 100ms

        // Seed at t=0
        assertThat(cache.get("k", () -> "v0")).isEqualTo("v0");

        // t=50: fresh expired (>=10), still within stale (<100); loader fails -> serve stale
        clock.set(50);
        assertThat(cache.get("k", () -> { throw new RuntimeException("boom"); })).isEqualTo("v0");

        // t=200: beyond stale (>=100); loader fails -> propagate
        clock.set(200);
        assertThatThrownBy(() -> cache.get("k", () -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }

    @Test
    void staleWindow_refreshesExactlyOnceUnderConcurrency() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        TtlSingleFlightCache<String> cache =
                new TtlSingleFlightCache<>(10L, 60_000L, clock::get);
        AtomicInteger loads = new AtomicInteger();

        // Seed at t=0 (load #1)
        cache.get("k", () -> { loads.incrementAndGet(); return "v0"; });

        // Enter stale window
        clock.set(50);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    cache.get("k", () -> {
                        loads.incrementAndGet();
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        return "v1";
                    });
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 1 seed load + exactly 1 refresh; the other 11 callers served stale without loading.
        assertThat(loads.get()).isEqualTo(2);
    }
}
