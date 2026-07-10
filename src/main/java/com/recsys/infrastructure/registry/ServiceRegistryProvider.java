package com.recsys.infrastructure.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically-refreshed view of the service registry. Reads are lock-free (a single volatile
 * immutable {@code Map}); a refresh atomically swaps it. On any refresh error the last-good snapshot
 * is retained — fail-static, so registry I/O never breaks the request path.
 */
public final class ServiceRegistryProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistryProvider.class);

    private final ServiceRegistryStore store;
    private final List<String> serviceNames;
    private final long refreshMs;
    private final Runnable onRefresh;

    private volatile Map<String, String> snapshot = Map.of();
    ScheduledExecutorService scheduler; // package-private for shutdown assertions

    public ServiceRegistryProvider(ServiceRegistryStore store, Collection<String> serviceNames,
                                   long refreshMs, Runnable onRefresh) {
        this.store = store;
        this.serviceNames = List.copyOf(serviceNames);
        this.refreshMs = refreshMs;
        this.onRefresh = onRefresh == null ? () -> {} : onRefresh;
    }

    public void start() {
        refresh();
        if (refreshMs > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "svc-registry-refresh");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::refresh, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
        }
    }

    public void refresh() {
        try {
            Map<String, String> loaded = store.lookup(serviceNames);
            this.snapshot = Map.copyOf(loaded);
        } catch (Exception e) {
            log.warn("Service registry refresh failed — keeping last-good snapshot: {}", e.toString());
            return;
        }
        try {
            onRefresh.run();
        } catch (Exception e) {
            log.warn("Service registry onRefresh callback failed (non-fatal): {}", e.toString());
        }
    }

    public Optional<String> resolve(String serviceName) {
        if (serviceName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get(serviceName));
    }

    public void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
