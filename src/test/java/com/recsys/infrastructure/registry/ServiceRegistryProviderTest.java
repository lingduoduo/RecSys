package com.recsys.infrastructure.registry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ServiceRegistryProviderTest {

    @Test
    void refreshSwapsSnapshotAndResolves() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection())).thenReturn(Map.of("a", "http://a:1"));
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, null);

        p.refresh();

        assertThat(p.resolve("a")).contains("http://a:1");
        assertThat(p.resolve("missing")).isEmpty();
    }

    @Test
    void failStaticKeepsLastGoodSnapshot() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection()))
                .thenReturn(Map.of("a", "http://a:1"))
                .thenThrow(new RuntimeException("redis down"));
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, null);

        p.refresh();          // good
        p.refresh();          // throws internally -> keep last good

        assertThat(p.resolve("a")).contains("http://a:1");
    }

    @Test
    void lastRefreshAtMsIsZeroUntilSuccessThenSet() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection()))
                .thenReturn(Map.of("a", "http://a:1"))
                .thenThrow(new RuntimeException("redis down"));
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, null);

        assertThat(p.lastRefreshAtMs()).isZero();
        p.refresh();
        long afterGood = p.lastRefreshAtMs();
        assertThat(afterGood).isGreaterThan(0L);
        p.refresh(); // fails internally -> timestamp unchanged
        assertThat(p.lastRefreshAtMs()).isEqualTo(afterGood);
    }

    @Test
    void refreshCountersTrackSuccessAndFailure() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection()))
                .thenReturn(Map.of("a", "http://a:1"))
                .thenThrow(new RuntimeException("redis down"))
                .thenReturn(Map.of("a", "http://a:1"));
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, null);

        assertThat(p.refreshSuccessCount()).isZero();
        assertThat(p.refreshFailureCount()).isZero();
        p.refresh();  // success
        p.refresh();  // failure (kept static)
        p.refresh();  // success
        assertThat(p.refreshSuccessCount()).isEqualTo(2L);
        assertThat(p.refreshFailureCount()).isEqualTo(1L);
    }

    @Test
    void onRefreshCallbackFiresAfterSwap() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        when(store.lookup(Mockito.anyCollection())).thenReturn(Map.of("a", "http://a:1"));
        int[] calls = {0};
        ServiceRegistryProvider p = new ServiceRegistryProvider(store, List.of("a"), 0L, () -> calls[0]++);

        p.refresh();

        assertThat(calls[0]).isEqualTo(1);
    }
}
