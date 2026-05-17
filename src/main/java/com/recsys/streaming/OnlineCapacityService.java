package com.recsys.streaming;

/**
 * Keeps production sizing assumptions visible at runtime.
 */
public final class OnlineCapacityService {
    private static final long DEFAULT_TARGET_DAU = 2_000_000L;
    private static final long DEFAULT_PEAK_QPS = 8_000L;
    private static final long DEFAULT_PEAK_TPS = 20_000L;

    private final long targetDau;
    private final long peakQps;
    private final long peakTps;

    public OnlineCapacityService() {
        this(
                readLongEnv("ONLINE_TARGET_DAU", DEFAULT_TARGET_DAU),
                readLongEnv("ONLINE_PEAK_QPS", DEFAULT_PEAK_QPS),
                readLongEnv("ONLINE_PEAK_TPS", DEFAULT_PEAK_TPS)
        );
    }

    OnlineCapacityService(long targetDau, long peakQps, long peakTps) {
        this.targetDau = Math.max(1L, targetDau);
        this.peakQps = Math.max(1L, peakQps);
        this.peakTps = Math.max(1L, peakTps);
    }

    public Snapshot snapshot(OnlineServingMetricsService.Snapshot metrics,
                             OnlineLoadShedder.Snapshot load) {
        double observedQps = metrics == null ? 0.0 : metrics.qps();
        double qpsUtilization = observedQps / peakQps;
        return new Snapshot(
                targetDau,
                peakQps,
                peakTps,
                observedQps,
                qpsUtilization,
                load,
                "Redis + MQ/Kafka peak shaving: Kafka absorbs bursty TPS; Flink writes compact Redis aggregates; stateless API instances serve peak QPS."
        );
    }

    private static long readLongEnv(String envName, long defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record Snapshot(
            long targetDau,
            long peakQps,
            long peakTps,
            double observedQps,
            double qpsUtilization,
            OnlineLoadShedder.Snapshot load,
            String peakShaving
    ) {}
}
