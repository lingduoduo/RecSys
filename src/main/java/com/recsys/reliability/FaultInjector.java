package com.recsys.reliability;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class FaultInjector {

    public static final FaultInjector NOOP = new FaultInjector(true) {
        @Override public void injectLatency(String point, long millis) {}
        @Override public void injectException(String point, RuntimeException ex) {}
        @Override public void clear(String point) {}
        @Override public void maybeInject(String point) {}
    };

    private enum FaultType { LATENCY, EXCEPTION }

    private record FaultConfig(FaultType type, long latencyMs, RuntimeException exception) {}

    // null in NOOP instances — all mutating methods are overridden to no-ops
    private final ConcurrentHashMap<String, FaultConfig> faults;

    public FaultInjector() {
        this.faults = new ConcurrentHashMap<>();
    }

    private FaultInjector(boolean noop) {
        this.faults = null; // NOOP — never accessed
    }

    public void injectLatency(String point, long millis) {
        Objects.requireNonNull(point, "point");
        if (millis < 0) throw new IllegalArgumentException("millis must be >= 0, got " + millis);
        faults.put(point, new FaultConfig(FaultType.LATENCY, millis, null));
    }

    public void injectException(String point, RuntimeException ex) {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(ex, "ex");
        faults.put(point, new FaultConfig(FaultType.EXCEPTION, 0, ex));
    }

    public void clear(String point) {
        Objects.requireNonNull(point, "point");
        faults.remove(point);
    }

    public void maybeInject(String point) {
        FaultConfig config = faults.get(point);
        if (config == null) return;
        switch (config.type()) {
            case LATENCY -> {
                try {
                    Thread.sleep(config.latencyMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            case EXCEPTION -> throw config.exception();
        }
    }
}
