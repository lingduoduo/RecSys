package com.recsys.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Installs explicit histogram buckets on Armeria's request-duration timer, on every registry it
 * is bound to.
 *
 * <p>{@code MetricCollectingService.newDecorator(...)} (see {@code RecSysServer},
 * {@code MicroserviceGatewayServer}, {@code OnlinePredictionServer}) times each request into a
 * meter named {@code <prefix>.request.duration}, but Armeria's default
 * {@link DistributionStatisticConfig} for that timer only publishes client-side percentiles
 * (the Prometheus {@code quantile=} series: {@code _count}/{@code _sum}/{@code _max} plus nine
 * {@code quantile=} lines). It does not publish {@code _bucket} series. A
 * {@code histogram_quantile(...)} alert query — which needs server-side aggregatable buckets, not
 * client-computed quantiles — matches nothing against that shape: it would sit in Prometheus
 * looking like coverage and never fire. This class exists to close that gap without touching the
 * decorator call sites: a {@link MeterFilter} that merges explicit
 * {@linkplain DistributionStatisticConfig#getServiceLevelObjectiveBoundaries() SLO boundaries}
 * onto any meter whose name ends in {@code .request.duration}, which is what turns those buckets
 * on.
 *
 * <p>Boundaries are deliberately explicit ({@link #BOUNDARIES}) rather than
 * {@code percentilesHistogram(true)}: the latter publishes Micrometer's ~70-bucket default
 * histogram per unique tag combination, and the tag set here
 * ({@code hostname.pattern x http.status x method x service}) already carries real cardinality.
 * Eight explicit buckets bracketing the three latency alert thresholds (0.4s online serving, 1s
 * catalog serving, 2s gateway) give {@code histogram_quantile} the same interpolation power at a
 * small fraction of the series count.
 *
 * <p>This does not replace the existing {@code quantile=} series — the filter <em>merges</em> its
 * SLO boundaries onto Armeria's existing config rather than replacing it, so the client-side
 * percentiles Armeria already publishes keep publishing. Removing a series that has been on the
 * wire since 7010's decorator was first added risks breaking a consumer this change cannot
 * enumerate.
 *
 * <p>Must be called before the first request-duration timer is registered on a given registry —
 * a {@link MeterFilter} only affects meters registered after it is added, so this belongs next to
 * {@link JvmMetricsBinder#bindTo(MeterRegistry)} at server startup, not after routes start
 * serving traffic.
 */
public final class RequestDurationHistogram {

    private static final Logger log = LoggerFactory.getLogger(RequestDurationHistogram.class);

    private static final String METER_NAME_SUFFIX = ".request.duration";

    /**
     * Bucket boundaries, in seconds: 50ms, 100ms, 250ms, 400ms, 500ms, 1s, 2s, 5s.
     * {@code +Inf} is added automatically by the Prometheus histogram exposition format, not
     * listed here. 400ms/1s/2s straddle each service's own alert threshold on both sides so
     * {@code histogram_quantile} can interpolate at exactly the value the alert cares about,
     * rather than only ever rounding to the nearest configured bucket.
     */
    static final Duration[] BOUNDARIES = {
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(250),
            Duration.ofMillis(400),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
    };

    /**
     * {@link DistributionStatisticConfig#serviceLevelObjectives(double...)} takes raw values in
     * the meter's base unit, which for a {@link io.micrometer.core.instrument.Timer} is
     * nanoseconds — the same conversion {@code Timer.Builder#serviceLevelObjectives(Duration...)}
     * performs internally. Computed once; {@link Duration#toNanos()} is not free enough to redo
     * per meter registration.
     */
    private static final double[] BOUNDARY_NANOS = toNanos(BOUNDARIES);

    /**
     * {@code PrometheusMeterRegistries.defaultRegistry()} is a JVM-wide singleton that more than
     * one caller may reasonably configure. A {@link MeterFilter} is cheap to add twice — merging
     * the same SLOs onto a config twice is a no-op in effect — but doing it anyway guards against
     * ever needing a reason it isn't (e.g. a future filter here with real side effects), and
     * matches {@link JvmMetricsBinder}'s guard on the same registry. Identity comparison, because
     * {@code MeterRegistry} does not define equality.
     */
    private static final Set<MeterRegistry> CONFIGURED =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private RequestDurationHistogram() {}

    private static double[] toNanos(Duration[] durations) {
        double[] nanos = new double[durations.length];
        for (int i = 0; i < durations.length; i++) {
            nanos[i] = durations[i].toNanos();
        }
        return nanos;
    }

    public static synchronized void configure(MeterRegistry registry) {
        if (registry == null || !CONFIGURED.add(registry)) {
            return;
        }
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!id.getName().endsWith(METER_NAME_SUFFIX)) {
                    return config;
                }
                // Build a config with only the SLO boundaries set, then merge the original
                // (Armeria's own percentiles config) as the parent: merge() fills in any field
                // left unset on the child from the parent, so Armeria's existing
                // percentiles/percentileHistogram settings survive untouched and only the SLO
                // boundaries are new.
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(BOUNDARY_NANOS)
                        .build()
                        .merge(config);
            }
        });
        log.info("Installed explicit histogram buckets ({}) on *.request.duration meters",
                Arrays.toString(BOUNDARIES));
    }
}
