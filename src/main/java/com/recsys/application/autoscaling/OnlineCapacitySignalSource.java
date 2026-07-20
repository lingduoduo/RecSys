package com.recsys.application.autoscaling;

import com.recsys.health.OnlineCapacityService;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Maps the live {@link OnlineCapacityService.Snapshot} to a {@link CapacitySignal} — QPS utilization
 * as the tracked metric, {@code overloaded} (QPS saturation OR load-shedder drain) as the surge flag.
 * Demonstrates wiring the reference {@link CapacityController} to real signals; not scheduled by any
 * server.
 */
public final class OnlineCapacitySignalSource implements CapacitySignalSource {
    private final Supplier<OnlineCapacityService.Snapshot> snapshot;

    public OnlineCapacitySignalSource(Supplier<OnlineCapacityService.Snapshot> snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override
    public CapacitySignal read() {
        OnlineCapacityService.Snapshot s = snapshot.get();
        return new CapacitySignal(s.qpsUtilization(), s.overloaded());
    }
}
