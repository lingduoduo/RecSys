package com.recsys.jvm;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Event-driven GC observability via JMX {@link GarbageCollectionNotificationInfo}.
 *
 * Unlike the poll-based {@link JvmMemoryMonitor}, this class receives a callback on every
 * GC event and can therefore produce accurate per-pause histograms, allocation rates derived
 * from pool snapshots, and real-time detection of G1 evacuation failures.
 *
 * GC taxonomy tracked:
 *   Minor GC   – young-gen only; STW; triggered when Eden fills (G1 Young, ParNew, Serial)
 *   Mixed GC   – G1 only; reclaims young gen + selected old-gen regions; STW
 *   Full GC    – entire heap; STW; most disruptive; triggered by old-gen pressure or System.gc()
 *   CMS phase  – recognized for legacy JVM compatibility (deprecated Java 9, removed Java 14)
 *   ZGC cycle  – concurrent cycle; reported wall-time includes concurrent phases; actual STW ≪ 1 ms
 *
 * STW pause histogram buckets (ms): <1, 1–10, 10–50, 50–200, 200–500, >500
 * A growing >500 ms bucket signals Full GC pressure and requires immediate heap/GC tuning.
 */
@Service
public class GcEventTracker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GcEventTracker.class);

    // Pause histogram bucket upper bounds (ms). Final bucket is open-ended (>500 ms).
    private static final long[] HISTOGRAM_BOUNDS = {1, 10, 50, 200, 500};
    private static final String[] HISTOGRAM_LABELS = {"<1ms", "1-10ms", "10-50ms", "50-200ms", "200-500ms", ">500ms"};

    private final LongAdder[] typeCounts = new LongAdder[GcType.values().length];
    private final LongAdder[] typePauseMs = new LongAdder[GcType.values().length];
    private final LongAdder[] histogramBuckets = new LongAdder[HISTOGRAM_LABELS.length];

    private final LongAdder stwEventCount    = new LongAdder();
    private final LongAdder stwTotalPauseMs  = new LongAdder();
    private final AtomicLong stwLongestPauseMs = new AtomicLong(0);

    private final LongAdder totalAllocatedBytes  = new LongAdder();
    private final LongAdder totalPromotedBytes    = new LongAdder();

    private final LongAdder evacuationFailures = new LongAdder();
    private final LongAdder allocationStalls   = new LongAdder();

    private record ListenerRegistration(NotificationEmitter emitter, NotificationListener listener) {}
    private final List<ListenerRegistration> registrations = new java.util.concurrent.CopyOnWriteArrayList<>();

    public GcEventTracker() {
        for (GcType t : GcType.values()) {
            typeCounts[t.ordinal()]  = new LongAdder();
            typePauseMs[t.ordinal()] = new LongAdder();
        }
        Arrays.setAll(histogramBuckets, i -> new LongAdder());
    }

    /**
     * Registers the JMX notification listeners. Spring calls this via {@link PostConstruct}; the
     * three Armeria mains, which have no container, call it directly during boot. Idempotent —
     * a second call would double-count every pause.
     */
    @PostConstruct
    public synchronized void start() {
        if (!registrations.isEmpty()) {
            return;
        }
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gc instanceof NotificationEmitter emitter)) continue;
            NotificationListener listener = this::onNotification;
            emitter.addNotificationListener(listener, null, null);
            registrations.add(new ListenerRegistration(emitter, listener));
            log.debug("GcEventTracker: registered on collector '{}'", gc.getName());
        }
    }

    /** Removes the listeners. Idempotent. */
    public synchronized void stop() {
        for (ListenerRegistration r : registrations) {
            try {
                r.emitter().removeNotificationListener(r.listener());
            } catch (Exception e) {
                log.debug("GcEventTracker: failed to remove listener: {}", e.getMessage());
            }
        }
        registrations.clear();
    }

    @Override
    public void destroy() {
        stop();
    }

    /**
     * Test-only view of how many JMX listeners are currently registered. Not for production
     * use — it exists so a test can assert on the one thing the empty-check guard in
     * {@link #start()} is load-bearing for: that a second {@code start()} call does not install
     * a second listener per collector (which would double-count every GC pause), and that
     * {@link #stop()} actually tears every one of them back down.
     */
    synchronized int registeredCollectorCount() {
        return registrations.size();
    }

    // ── Notification handler ─────────────────────────────────────────────────

    private void onNotification(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                .equals(notification.getType())) return;

        GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        GcInfo gcInfo = info.getGcInfo();

        GcType type  = classifyEvent(info.getGcAction(), info.getGcName(), info.getGcCause());
        long pauseMs = gcInfo.getDuration();

        typeCounts[type.ordinal()].increment();
        typePauseMs[type.ordinal()].add(pauseMs);

        if (type.stw) {
            recordStwPause(pauseMs);
        }

        recordMemoryDeltas(gcInfo.getMemoryUsageBeforeGc(), gcInfo.getMemoryUsageAfterGc());
        recordDangerSignals(info.getGcCause());
    }

    private void recordStwPause(long pauseMs) {
        stwEventCount.increment();
        stwTotalPauseMs.add(pauseMs);
        stwLongestPauseMs.accumulateAndGet(pauseMs, Math::max);
        histogramBuckets[bucketIndex(pauseMs)].increment();
    }

    private void recordMemoryDeltas(Map<String, MemoryUsage> before, Map<String, MemoryUsage> after) {
        long edenBefore = 0, edenAfter = 0, oldBefore = 0, oldAfter = 0;
        for (Map.Entry<String, MemoryUsage> entry : before.entrySet()) {
            MemoryUsage afterUsage = after.get(entry.getKey());
            if (afterUsage == null) continue;
            String pool     = entry.getKey().toLowerCase(Locale.ROOT);
            long usedBefore = entry.getValue().getUsed();
            long usedAfter  = afterUsage.getUsed();

            if (pool.contains("eden") || (pool.contains("young") && !pool.contains("old"))) {
                edenBefore += usedBefore;
                edenAfter  += usedAfter;
            } else if (pool.contains("old") || pool.contains("tenured")) {
                oldBefore += usedBefore;
                oldAfter  += usedAfter;
            }
        }
        // Bytes allocated = Eden space consumed since last collection (Eden is emptied each GC)
        if (edenBefore > edenAfter) {
            totalAllocatedBytes.add(edenBefore - edenAfter);
        }
        // Bytes promoted = net old-gen growth (negative means old gen was also collected)
        long promoted = oldAfter - oldBefore;
        if (promoted > 0) {
            totalPromotedBytes.add(promoted);
        }
    }

    private void recordDangerSignals(String cause) {
        if (cause == null) return;
        String lower = cause.toLowerCase(Locale.ROOT);
        // G1 evacuation failure: not enough free regions to copy survivors; triggers Full GC fallback
        if (lower.contains("evacuation failure") || lower.contains("g1 evacuation")) {
            evacuationFailures.increment();
        }
        // ZGC allocation stall: mutator threads blocked waiting for GC to free memory
        if (lower.contains("allocation stall")) {
            allocationStalls.increment();
        }
    }

    // ── Classification ───────────────────────────────────────────────────────

    private static GcType classifyEvent(String action, String name, String cause) {
        String actionLow = action == null ? "" : action.toLowerCase(Locale.ROOT);
        String nameLow   = name   == null ? "" : name.toLowerCase(Locale.ROOT);
        String causeLow  = cause  == null ? "" : cause.toLowerCase(Locale.ROOT);

        // ZGC reports concurrent cycles and separate STW pauses
        if (nameLow.contains("zgc")) {
            // "ZGC Pauses" → STW initial-mark / final-mark / relocate-start
            if (nameLow.contains("pause") || actionLow.contains("minor")) return GcType.ZGC_STW_PAUSE;
            return GcType.ZGC_CYCLE;  // concurrent cycle (wall time, not pure STW)
        }
        // Shenandoah follows the same concurrent pattern
        if (nameLow.contains("shenandoah")) {
            return nameLow.contains("pause") ? GcType.ZGC_STW_PAUSE : GcType.ZGC_CYCLE;
        }
        // CMS: recognized for legacy JVM visibility; deprecated in Java 9, removed in Java 14
        if (nameLow.contains("cms") || nameLow.contains("concurrentmarksweep")) {
            return GcType.CMS_PHASE;
        }
        // G1 Mixed: collects young + selected old-gen regions; always STW
        if (nameLow.contains("mixed") || causeLow.contains("mixed")) return GcType.G1_MIXED;

        if (actionLow.contains("minor")) return GcType.MINOR_GC;
        if (actionLow.contains("major")) return GcType.FULL_GC;

        // Fallback: check name for young/old hints
        if (nameLow.contains("young") || nameLow.contains("scavenge") || nameLow.contains("parnew")) {
            return GcType.MINOR_GC;
        }
        if (nameLow.contains("full") || nameLow.contains("old") || nameLow.contains("mark")) {
            return GcType.FULL_GC;
        }
        return GcType.FULL_GC; // conservative: treat unknowns as full to avoid under-counting
    }

    private static int bucketIndex(long pauseMs) {
        for (int i = 0; i < HISTOGRAM_BOUNDS.length; i++) {
            if (pauseMs < HISTOGRAM_BOUNDS[i]) return i;
        }
        return HISTOGRAM_LABELS.length - 1;
    }

    // ── Snapshot ─────────────────────────────────────────────────────────────

    public Snapshot snapshot() {
        Map<GcType, TypeStats> byType = new LinkedHashMap<>();
        for (GcType t : GcType.values()) {
            long count = typeCounts[t.ordinal()].sum();
            if (count > 0) {
                byType.put(t, new TypeStats(t, count, typePauseMs[t.ordinal()].sum()));
            }
        }

        Map<String, Long> histogram = new LinkedHashMap<>();
        for (int i = 0; i < HISTOGRAM_LABELS.length; i++) {
            histogram.put(HISTOGRAM_LABELS[i], histogramBuckets[i].sum());
        }

        long stwCount    = stwEventCount.sum();
        long stwTotalMs  = stwTotalPauseMs.sum();
        long stwLongest  = stwLongestPauseMs.get();
        double stwAvgMs  = stwCount > 0 ? (double) stwTotalMs / stwCount : 0.0;

        return new Snapshot(
                byType,
                stwCount, stwTotalMs, stwLongest, stwAvgMs,
                histogram,
                totalAllocatedBytes.sum(),
                totalPromotedBytes.sum(),
                evacuationFailures.sum(),
                allocationStalls.sum()
        );
    }

    // ── Public types ─────────────────────────────────────────────────────────

    /**
     * GC event type with STW flag and human-readable description.
     *
     * {@code stw=true} means the JVM halted all application threads for the full duration.
     * {@code stw=false} means the GC ran mostly concurrently; actual STW sub-phases are short.
     */
    public enum GcType {
        MINOR_GC    ("Young/minor GC — Eden + Survivor; STW; normal and frequent",           true),
        G1_MIXED    ("G1 mixed GC — young + selected old-gen regions; STW",                  true),
        FULL_GC     ("Full GC — entire heap; STW; most disruptive; investigate if recurring", true),
        CMS_PHASE   ("CMS concurrent phase — deprecated collector; low STW but fragile",      false),
        ZGC_CYCLE   ("ZGC/Shenandoah concurrent cycle — wall time; actual STW < 1 ms",       false),
        ZGC_STW_PAUSE("ZGC STW phase — initial/final mark, relocate-start; target < 1 ms",  true);

        public final String description;
        public final boolean stw;

        GcType(String description, boolean stw) {
            this.description = description;
            this.stw = stw;
        }
    }

    /**
     * Per-GC-type aggregate counts and cumulative pause time.
     *
     * For {@link GcType#ZGC_CYCLE}, {@code totalPauseMs} is wall-clock cycle time, not pure STW.
     * Use {@link Snapshot#stwTotalPauseMs} for accurate STW accounting across all types.
     */
    public record TypeStats(GcType type, long events, long totalPauseMs) {
        public double avgPauseMs() {
            return events > 0 ? (double) totalPauseMs / events : 0.0;
        }
    }

    /**
     * Point-in-time GC observability snapshot.
     *
     * <h3>Interpreting STW fields</h3>
     * {@code stwLongestPauseMs} is the single worst pause since JVM start.
     * A value above {@code MaxGCPauseMillis} (default 200 ms, tuned to 100 ms here) means
     * G1 failed to meet its pause target — look for humongous allocations or old-gen pressure.
     *
     * <h3>Interpreting allocation / promotion rates</h3>
     * These are cumulative totals; divide by uptime seconds to get rates (bytes/s).
     * A promotion rate above ~50 MB/s on a 2 GB heap indicates short-lived objects are
     * being tenured prematurely — consider widening the young generation.
     *
     * <h3>Danger signals</h3>
     * {@code evacuationFailures > 0} means G1 ran out of free regions during a collection.
     * This causes an internal full GC and indicates heap fragmentation or undersized old gen.
     * {@code allocationStalls > 0} is ZGC-specific: mutator threads had to wait for GC.
     */
    public record Snapshot(
            Map<GcType, TypeStats> byType,
            long stwEventCount,
            long stwTotalPauseMs,
            long stwLongestPauseMs,
            double stwAvgPauseMs,
            Map<String, Long> stwPauseHistogram,
            long totalAllocatedBytes,
            long totalPromotedBytes,
            long evacuationFailures,
            long allocationStalls
    ) {}
}
