package com.recsys.infrastructure.registry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class ServiceRegistrarTest {

    @Test
    void heartbeatRegistersWithConfiguredTtl() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        ServiceRegistrar reg = new ServiceRegistrar(store, "svc-a", "http://a:1", 1000L, 3000L);
        reg.heartbeat();
        verify(store).register("svc-a", "http://a:1", 3000L);
    }

    @Test
    void heartbeatSwallowsStoreErrors() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        doThrow(new RuntimeException("redis down")).when(store)
                .register(Mockito.any(), Mockito.any(), Mockito.anyLong());
        ServiceRegistrar reg = new ServiceRegistrar(store, "svc-a", "http://a:1", 1000L, 3000L);
        reg.heartbeat(); // must not throw
    }

    @Test
    void closeBestEffortDeregisters() {
        ServiceRegistryStore store = Mockito.mock(ServiceRegistryStore.class);
        ServiceRegistrar reg = new ServiceRegistrar(store, "svc-a", "http://a:1", 1000L, 3000L);
        reg.start();
        reg.close();
        verify(store, atLeastOnce()).register("svc-a", "http://a:1", 3000L);
        verify(store).deregister("svc-a");
    }
}
