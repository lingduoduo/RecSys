package com.recsys.online.ops;

import com.recsys.infrastructure.EnvConfig;

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
                EnvConfig.readLong("ONLINE_TARGET_DAU", DEFAULT_TARGET_DAU),
                EnvConfig.readLong("ONLINE_PEAK_QPS", DEFAULT_PEAK_QPS),
                EnvConfig.readLong("ONLINE_PEAK_TPS", DEFAULT_PEAK_TPS)
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
        double headroomQps = Math.max(0.0, peakQps - observedQps);
        boolean overloaded = qpsUtilization >= 1.0
                || (load != null && load.utilization() >= load.drainUtilization());
        return new Snapshot(
                targetDau,
                peakQps,
                peakTps,
                observedQps,
                qpsUtilization,
                headroomQps,
                overloaded,
                load,
                "Redis + MQ/Kafka peak shaving: Kafka absorbs bursty TPS; Flink writes compact Redis aggregates; stateless API instances serve peak QPS."
        );
    }

    public record Snapshot(
            long targetDau,
            long peakQps,
            long peakTps,
            double observedQps,
            double qpsUtilization,
            double headroomQps,
            boolean overloaded,
            OnlineLoadShedder.Snapshot load,
            String peakShaving
    ) {}
}
