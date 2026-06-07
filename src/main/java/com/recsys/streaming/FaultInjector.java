package com.recsys.streaming;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class FaultInjector {

    public static final FaultInjector NOOP = new FaultInjector() {
        @Override public void injectLatency(String point, long millis) {}
        @Override public void injectException(String point, RuntimeException ex) {}
        @Override public void clear(String point) {}
        @Override public void maybeInject(String point) {}
    };

    private enum FaultType { LATENCY, EXCEPTION }

    private record FaultConfig(FaultType type, long latencyMs, RuntimeException exception) {}

    private final ConcurrentHashMap<String, FaultConfig> faults = new ConcurrentHashMap<>();

    public void injectLatency(String point, long millis) {
        Objects.requireNonNull(point, "point");
        faults.put(point, new FaultConfig(FaultType.LATENCY, millis, null));
    }

    public void injectException(String point, RuntimeException ex) {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(ex, "ex");
        faults.put(point, new FaultConfig(FaultType.EXCEPTION, 0, ex));
    }

    public void clear(String point) {
        faults.remove(point);
    }

    public void maybeInject(String point) {
        FaultConfig config = faults.get(point);
        if (config == null) return;
        if (config.type() == FaultType.LATENCY) {
            try {
                Thread.sleep(config.latencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            throw config.exception();
        }
    }
}
