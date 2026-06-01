package com.recsys.infrastructure.alb;

import java.util.Objects;

public record AlbTarget(String host, int port, TargetHealth health) {

    public enum TargetHealth { HEALTHY, UNHEALTHY, DRAINING }

    public AlbTarget {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(health, "health");
        if (host.isBlank()) throw new IllegalArgumentException("host must not be blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
    }

    public static AlbTarget of(String host, int port) {
        return new AlbTarget(host, port, TargetHealth.HEALTHY);
    }

    public AlbTarget withHealth(TargetHealth newHealth) {
        return new AlbTarget(host, port, newHealth);
    }

    public boolean isRoutable() {
        return health == TargetHealth.HEALTHY;
    }

    @Override
    public String toString() {
        return host + ":" + port + " [" + health + "]";
    }
}
