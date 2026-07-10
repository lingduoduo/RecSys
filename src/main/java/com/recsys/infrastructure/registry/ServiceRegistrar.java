package com.recsys.infrastructure.registry;

import com.recsys.config.EnvVars;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Registers one service's advertised address into the {@link ServiceRegistryStore} and renews it on a
 * daemon heartbeat. All store I/O is best-effort — failures are logged and never break serving.
 */
public final class ServiceRegistrar implements java.io.Closeable {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistrar.class);

    private final ServiceRegistryStore store;
    private final String serviceName;
    private final String address;
    private final long heartbeatMs;
    private final long ttlMs;

    private ScheduledExecutorService scheduler;
    private volatile boolean closed;

    public ServiceRegistrar(ServiceRegistryStore store, String serviceName, String address,
                            long heartbeatMs, long ttlMs) {
        this.store = store;
        this.serviceName = serviceName;
        this.address = address;
        this.heartbeatMs = heartbeatMs;
        this.ttlMs = ttlMs;
    }

    /** Builds a registrar from env, or returns null when disabled/misconfigured. */
    public static ServiceRegistrar fromEnvironment(ServiceRegistryStore store) {
        if (!EnvVars.readBool("SERVICE_REGISTRY_ENABLED", false)) {
            return null;
        }
        String name = System.getenv("SERVICE_REGISTRY_SERVICE_NAME");
        String address = System.getenv("SERVICE_REGISTRY_ADVERTISE_URL");
        if (name == null || name.isBlank() || address == null || address.isBlank()) {
            log.warn("SERVICE_REGISTRY_ENABLED but SERVICE_REGISTRY_SERVICE_NAME/ADVERTISE_URL unset — not registering");
            return null;
        }
        long heartbeatMs = EnvVars.readLong("SERVICE_REGISTRY_HEARTBEAT_MS", 10_000L);
        long ttlMs = EnvVars.readLong("SERVICE_REGISTRY_TTL_MS", 30_000L);
        return new ServiceRegistrar(store, name, address, heartbeatMs, ttlMs);
    }

    public void start() {
        heartbeat();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "svc-registry-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::heartbeat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        log.info("Service registry: registered '{}' -> {} (heartbeat {} ms, ttl {} ms)",
                serviceName, address, heartbeatMs, ttlMs);
    }

    void heartbeat() {
        try {
            store.register(serviceName, address, ttlMs);
        } catch (Exception e) {
            log.warn("Service registry heartbeat for '{}' failed (non-fatal): {}", serviceName, e.toString());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        try {
            store.deregister(serviceName);
        } catch (Exception e) {
            log.warn("Service registry deregister for '{}' failed (non-fatal): {}", serviceName, e.toString());
        }
    }
}
