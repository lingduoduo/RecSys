package com.recsys.infrastructure.redis.sharding;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShardTopologyProviderTest {

    private static ShardTopologyStore.Snapshot snap(int v, int shards, long created,
            Integer pv, Integer ps, Long pExp) {
        return new ShardTopologyStore.Snapshot(v, shards, 150, created, pv, ps, pExp, null, null);
    }

    /**
     * Provider wired to a stubbed store returning {@code stored}, with a clock fixed below
     * the snapshot's {@code prevExpiresAtMs} so {@code previousIfActive()} returns non-null.
     */
    private static ShardTopologyProvider providerReading(ShardTopologyStore.Snapshot stored) {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(stored);
        return new ShardTopologyProvider(store, 150, 2, 30_000L, () -> 0L);
    }

    @Test
    void refresh_adoptsCurrentVersionFromStore() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(snap(3, 8, 100L, null, null, null));
        AtomicLong clock = new AtomicLong(500L);
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, clock::get);

        p.refresh();

        assertThat(p.current().version()).isEqualTo(3);
        assertThat(p.current().shardCount()).isEqualTo(8);
        assertThat(p.previousIfActive()).isNull();
    }

    @Test
    void previousIsActiveOnlyInsideWindow() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(snap(2, 4, 1000L, 1, 2, 5000L)); // prev expires at 5000
        AtomicLong clock = new AtomicLong(3000L);                        // inside window
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, clock::get);

        p.refresh();
        assertThat(p.previousIfActive()).isNotNull();
        assertThat(p.previousIfActive().version()).isEqualTo(1);
        assertThat(p.previousIfActive().shardCount()).isEqualTo(2);

        clock.set(5000L); // window closed (now >= expiry)
        assertThat(p.previousIfActive()).isNull();
    }

    @Test
    void refresh_keepsLastGoodSnapshotOnStoreFailure() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(snap(1, 2, 0L, null, null, null));
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, () -> 0L);
        p.refresh();
        assertThat(p.current().version()).isEqualTo(1);

        when(store.load()).thenThrow(new RuntimeException("redis down"));
        p.refresh(); // must not throw, must not null out current

        assertThat(p.current().version()).isEqualTo(1);
    }

    @Test
    void refresh_survivesAJvmErrorFromTheStoreAndAdoptsTheNextGoodLoad() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(snap(1, 2, 0L, null, null, null));
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, () -> 0L);
        p.refresh();

        when(store.load()).thenThrow(new StackOverflowError("deserializer recursed"));
        p.refresh(); // must not throw, must keep last-good
        assertThat(p.current().version()).isEqualTo(1);

        org.mockito.Mockito.reset(store);
        when(store.load()).thenReturn(snap(2, 4, 5L, 1, 2, 999L));
        p.refresh();
        assertThat(p.current().version()).isEqualTo(2);
    }

    @Test
    void start_scheduleSurvivesAnErrorAndPublishesLoopHealth() throws Exception {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        // Four loads: start()'s own, the Error, the first good scheduled load, and one more —
        // the latch trips inside load(), before the snapshot swap, so waiting for the load
        // AFTER the good one is what guarantees the swap has happened.
        java.util.concurrent.CountDownLatch loadedAfterError = new java.util.concurrent.CountDownLatch(4);
        when(store.load()).thenAnswer(i -> {
            int n = loads.incrementAndGet();
            loadedAfterError.countDown();
            if (n == 2) throw new OutOfMemoryError("scan allocation");   // first SCHEDULED refresh
            return snap(n >= 3 ? 2 : 1, 2, 0L, null, null, null);
        });
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 20L, System::currentTimeMillis);
        p.loop().bindTo(registry);
        try {
            p.start();

            assertThat(loadedAfterError.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("refresh kept running after the Error").isTrue();
            assertThat(p.current().version()).isEqualTo(2);
            assertThat(registry.get(com.recsys.resilience.GuardedLoop.FAILURES)
                    .tag("loop", "shard-topology-refresh").functionCounter().count()).isGreaterThanOrEqualTo(1.0);
            assertThat(registry.get(com.recsys.resilience.GuardedLoop.SECONDS_SINCE_SUCCESS)
                    .tag("loop", "shard-topology-refresh").gauge().value()).isBetween(0.0, 5.0);
        } finally {
            p.stop();
        }
    }

    @Test
    void fixed_providesConstantVersionOneTopology() {
        ShardTopologyProvider p = ShardTopologyProvider.fixed(new ConsistentHashRing(2, 150));
        assertThat(p.current().version()).isEqualTo(1);
        assertThat(p.current().shardCount()).isEqualTo(2);
        assertThat(p.previousIfActive()).isNull();
    }

    @Test
    void stop_shutsDownScheduler() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        when(store.load()).thenReturn(snap(1, 2, 0L, null, null, null));
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, () -> 0L);
        p.start();
        assertThat(p.scheduler).isNotNull();

        p.stop();

        assertThat(p.scheduler.isShutdown()).isTrue();
    }

    @Test
    void stop_onUnstartedProvider_doesNotThrow() {
        ShardTopologyStore store = mock(ShardTopologyStore.class);
        ShardTopologyProvider p = new ShardTopologyProvider(store, 150, 2, 30_000L, () -> 0L);
        // never started -> scheduler is null
        p.stop(); // must not throw
        assertThat(p.scheduler).isNull();
    }

    @Test
    void refreshCarriesEachGenerationsOwnKeyFormat() {
        // A reshard from an untagged generation leaves current tagged and previous untagged.
        // Both must keep their own format, or the dual-read builds keys the writer never wrote.
        ShardTopologyStore.Snapshot stored = new ShardTopologyStore.Snapshot(
                2, 4, 150, 1_000L, 1, 2, Long.MAX_VALUE,
                ShardKeys.FORMAT_TAGGED, ShardKeys.FORMAT_UNTAGGED);

        ShardTopologyProvider provider = providerReading(stored);
        provider.refresh();

        assertThat(provider.current().keyFormat()).isEqualTo(ShardKeys.FORMAT_TAGGED);
        assertThat(provider.previousIfActive().keyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
    }

    @Test
    void aLegacyDocumentWithoutTheFieldYieldsUntaggedGenerations() {
        ShardTopologyStore.Snapshot legacy = new ShardTopologyStore.Snapshot(
                2, 4, 150, 1_000L, 1, 2, Long.MAX_VALUE, null, null);

        ShardTopologyProvider provider = providerReading(legacy);
        provider.refresh();

        assertThat(provider.current().keyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
        assertThat(provider.previousIfActive().keyFormat()).isEqualTo(ShardKeys.FORMAT_UNTAGGED);
    }
}
