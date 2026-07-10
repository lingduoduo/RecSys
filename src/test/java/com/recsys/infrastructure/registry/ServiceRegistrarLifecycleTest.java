package com.recsys.infrastructure.registry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.verify;

class ServiceRegistrarLifecycleTest {

    @Test
    void delegatesToNonNullRegistrar() {
        ServiceRegistrar registrar = Mockito.mock(ServiceRegistrar.class);
        ServiceRegistrarLifecycle lifecycle = new ServiceRegistrarLifecycle(registrar);
        lifecycle.start();
        lifecycle.close();
        verify(registrar).start();
        verify(registrar).close();
    }

    @Test
    void nullRegistrarIsNoOp() {
        ServiceRegistrarLifecycle lifecycle = new ServiceRegistrarLifecycle(null);
        lifecycle.start(); // must not throw
        lifecycle.close(); // must not throw
    }
}
