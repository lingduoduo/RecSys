package com.recsys.infrastructure.registry;

/**
 * Null-safe start/stop wrapper around a {@link ServiceRegistrar}, so a DI container (or any caller)
 * can manage a registrar uniformly whether or not the registry is enabled. When the wrapped registrar
 * is null — the feature is off or the advertise env vars are unset — both operations are no-ops.
 */
public final class ServiceRegistrarLifecycle implements java.io.Closeable {

    private final ServiceRegistrar registrar; // nullable

    public ServiceRegistrarLifecycle(ServiceRegistrar registrar) {
        this.registrar = registrar;
    }

    public void start() {
        if (registrar != null) {
            registrar.start();
        }
    }

    @Override
    public void close() {
        if (registrar != null) {
            registrar.close();
        }
    }
}
