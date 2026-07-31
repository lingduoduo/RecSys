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
