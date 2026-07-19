package com.recsys.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/** Bounded-cardinality metrics for durable delivery and read-your-writes consistency. */
public final class ConsistencyMetrics {
    private static final WeakHashMap<MeterRegistry, State> STATES = new WeakHashMap<>();
    public enum Destination { KAFKA_ONLINE, SAGA_SNS }
    public enum EventType { ONLINE_INTERACTION, SAGA_TRANSITION, OTHER }
    public enum TokenOutcome { VALID, INVALID, EXPIRED, SUBJECT_MISMATCH }
    public enum WaitOutcome { APPLIED, TIMEOUT, UNAVAILABLE }
    public enum ReconciliationOutcome { SCANNED, MISSING, REPUBLISHED, REPAIRED, FAILED }
    public record ReplicaLag(boolean available, double lagSeconds) {}

    private final EnumMap<Destination, Timer> deliveryLag = new EnumMap<>(Destination.class);
    private final EnumMap<Destination, Counter> deliveryFailures = new EnumMap<>(Destination.class);
    private final EnumMap<EventType, Counter> asyncDrops = new EnumMap<>(EventType.class);
    private final EnumMap<TokenOutcome, Counter> tokenValidations = new EnumMap<>(TokenOutcome.class);
    private final EnumMap<WaitOutcome, Counter> waitOutcomes = new EnumMap<>(WaitOutcome.class);
    private final EnumMap<ReconciliationOutcome, Counter> reconciliation = new EnumMap<>(ReconciliationOutcome.class);
    private final Timer waitDuration;
    private final State state;
    private final AtomicLong pending;
    private final AtomicLong inFlight;
    private final AtomicLong replicaAvailable;
    private final AtomicLong replicaLagBits;
    private final AtomicLong featureMin;
    private final AtomicLong featureMax;
    private final AtomicLong featureAgeBits;

    public ConsistencyMetrics(MeterRegistry registry) {
        this(registry, null);
    }

    public ConsistencyMetrics(MeterRegistry registry, LongSupplier pendingSupplier) {
        Objects.requireNonNull(registry, "registry");
        synchronized (STATES) {
            state = STATES.computeIfAbsent(registry, State::new);
        }
        if (pendingSupplier != null) state.pendingSupplier = pendingSupplier;
        deliveryLag.putAll(state.deliveryLag);
        deliveryFailures.putAll(state.deliveryFailures);
        asyncDrops.putAll(state.asyncDrops);
        tokenValidations.putAll(state.tokenValidations);
        waitOutcomes.putAll(state.waitOutcomes);
        reconciliation.putAll(state.reconciliation);
        waitDuration = state.waitDuration;
        pending = state.pending;
        inFlight = state.inFlight;
        replicaAvailable = state.replicaAvailable;
        replicaLagBits = state.replicaLagBits;
        featureMin = state.featureMin;
        featureMax = state.featureMax;
        featureAgeBits = state.featureAgeBits;
    }

    private static final class State {
        final EnumMap<Destination, Timer> deliveryLag = new EnumMap<>(Destination.class);
        final EnumMap<Destination, Counter> deliveryFailures = new EnumMap<>(Destination.class);
        final EnumMap<EventType, Counter> asyncDrops = new EnumMap<>(EventType.class);
        final EnumMap<TokenOutcome, Counter> tokenValidations = new EnumMap<>(TokenOutcome.class);
        final EnumMap<WaitOutcome, Counter> waitOutcomes = new EnumMap<>(WaitOutcome.class);
        final EnumMap<ReconciliationOutcome, Counter> reconciliation = new EnumMap<>(ReconciliationOutcome.class);
        final AtomicLong pending = new AtomicLong();
        final AtomicLong inFlight = new AtomicLong();
        final AtomicLong replicaAvailable = new AtomicLong();
        final AtomicLong replicaLagBits = new AtomicLong(Double.doubleToRawLongBits(Double.NaN));
        final AtomicLong featureMin = new AtomicLong();
        final AtomicLong featureMax = new AtomicLong();
        final AtomicLong featureAgeBits = new AtomicLong();
        volatile LongSupplier pendingSupplier;
        final Timer waitDuration;
        State(MeterRegistry registry) {
        for (Destination value : Destination.values()) {
            String tag = tag(value);
            deliveryLag.put(value, Timer.builder("outbox_delivery_lag_seconds").tag("destination", tag).register(registry));
            deliveryFailures.put(value, Counter.builder("outbox_delivery_failures_total").tag("destination", tag).register(registry));
        }
        for (EventType value : EventType.values())
            asyncDrops.put(value, Counter.builder("async_events_dropped_total").tag("event_type", tag(value)).register(registry));
        for (TokenOutcome value : TokenOutcome.values())
            tokenValidations.put(value, Counter.builder("consistency_token_validation_total").tag("outcome", tag(value)).register(registry));
        for (WaitOutcome value : WaitOutcome.values())
            waitOutcomes.put(value, Counter.builder("consistency_wait_total").tag("outcome", tag(value)).register(registry));
        for (ReconciliationOutcome value : ReconciliationOutcome.values())
            reconciliation.put(value, Counter.builder("reconciliation_events_total").tag("outcome", tag(value)).register(registry));
        waitDuration = Timer.builder("consistency_wait_duration_seconds").register(registry);
        Gauge.builder("outbox_pending_events", this, value -> value.pendingSupplier == null
                ? value.pending.get() : Math.max(0, value.pendingSupplier.getAsLong())).register(registry);
        Gauge.builder("outbox_in_flight_events", inFlight, AtomicLong::get).register(registry);
        Gauge.builder("redis_replica_lag_available", replicaAvailable, AtomicLong::get).register(registry);
        Gauge.builder("redis_replica_lag_seconds", replicaLagBits,
                bits -> Double.longBitsToDouble(bits.get())).register(registry);
        Gauge.builder("redis_feature_version_min", featureMin, AtomicLong::get).register(registry);
        Gauge.builder("redis_feature_version_max", featureMax, AtomicLong::get).register(registry);
        Gauge.builder("redis_feature_version_age_seconds", featureAgeBits,
                bits -> Double.longBitsToDouble(bits.get())).register(registry);
        }
    }

    public void recordDelivered(Destination destination, Duration lag) {
        deliveryLag.get(Objects.requireNonNull(destination)).record(nonnegative(lag));
    }
    public void recordDeliveryFailure(Destination destination) { deliveryFailures.get(Objects.requireNonNull(destination)).increment(); }
    public void recordAsyncDrop(EventType eventType) { asyncDrops.get(Objects.requireNonNull(eventType)).increment(); }
    public void recordTokenValidation(TokenOutcome outcome) { tokenValidations.get(Objects.requireNonNull(outcome)).increment(); }
    public void recordWait(WaitOutcome outcome, Duration duration) {
        waitOutcomes.get(Objects.requireNonNull(outcome)).increment();
        waitDuration.record(nonnegative(duration));
    }
    public void recordReconciliation(ReconciliationOutcome outcome) { reconciliation.get(Objects.requireNonNull(outcome)).increment(); }
    public void updatePendingEvents(long count) { pending.set(Math.max(0, count)); }
    public void updateInFlightEvents(long count) { inFlight.set(Math.max(0, count)); }
    public void updateReplicaLag(ReplicaLag result) {
        Objects.requireNonNull(result);
        replicaAvailable.set(result.available() ? 1 : 0);
        double lag = result.available() ? Math.max(0, result.lagSeconds()) : Double.NaN;
        replicaLagBits.set(Double.doubleToRawLongBits(lag));
    }
    public void updateFeatureVersions(long minimum, long maximum, Duration age) {
        featureMin.set(minimum); featureMax.set(maximum);
        featureAgeBits.set(Double.doubleToRawLongBits(nonnegative(age).toNanos() / 1_000_000_000d));
    }
    private static Duration nonnegative(Duration value) {
        Objects.requireNonNull(value); return value.isNegative() ? Duration.ZERO : value;
    }
    private static String tag(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
