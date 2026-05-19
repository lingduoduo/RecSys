package com.recsys.modelbased.model.service;

import org.springframework.stereotype.Service;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes a structured snapshot of the four JVM memory regions for observability and tuning.
 *
 * JVM memory model covered:
 *   堆  (Heap)      – object allocations; dominated here by float[] embedding vectors (old gen)
 *                     and per-inference intermediate arrays (young gen / eden).
 *   栈  (Stack)     – per-thread call frames; approximated via live thread count × -Xss.
 *   方法区/元空间     – class metadata, interned strings, JIT code cache; grows with Spring Boot +
 *                     ONNX Runtime class loading at startup then stabilises.
 *   (Non-heap)      – covers metaspace + compressed class space + code cache as sub-pools.
 *
 * All sizes are reported in bytes. usedFraction is used/max (or used/committed when max==-1).
 */
@Service
public class JvmMemoryMonitor {

    private static final long MB = 1024L * 1024L;

    public Snapshot snapshot() {
        MemoryUsage heapUsage    = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        ThreadMXBean threads     = ManagementFactory.getThreadMXBean();

        Map<String, PoolStats> heapPools    = new LinkedHashMap<>();
        Map<String, PoolStats> nonHeapPools = new LinkedHashMap<>();

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            PoolStats stats = new PoolStats(u.getUsed(), u.getCommitted(), u.getMax());
            if (pool.getType() == MemoryType.HEAP) {
                heapPools.put(pool.getName(), stats);
            } else {
                nonHeapPools.put(pool.getName(), stats);
            }
        }

        long youngGcCount = 0, youngGcMs = 0, fullGcCount = 0, fullGcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time  = gc.getCollectionTime();
            if (count < 0) continue;
            String name = gc.getName().toLowerCase();
            // G1 Young / Parallel Scavenge / Copy → young-gen collectors
            if (name.contains("young") || name.contains("scavenge") || name.contains("copy")) {
                youngGcCount += count;
                youngGcMs    += time;
            } else {
                fullGcCount += count;
                fullGcMs    += time;
            }
        }

        RegionStats heap = new RegionStats(
                heapUsage.getUsed(), heapUsage.getCommitted(), heapUsage.getMax(), heapPools);
        RegionStats nonHeap = new RegionStats(
                nonHeapUsage.getUsed(), nonHeapUsage.getCommitted(), nonHeapUsage.getMax(), nonHeapPools);
        ThreadStats threadStats = new ThreadStats(
                threads.getThreadCount(), threads.getDaemonThreadCount(), threads.getPeakThreadCount());
        GcStats gc = new GcStats(youngGcCount, youngGcMs, fullGcCount, fullGcMs);

        return new Snapshot(heap, nonHeap, threadStats, gc);
    }

    // ── Records ──────────────────────────────────────────────────────────────

    public record Snapshot(
            RegionStats heap,
            RegionStats nonHeap,
            ThreadStats threads,
            GcStats gc
    ) {}

    /**
     * Summary for one memory region (heap or non-heap) with per-pool breakdown.
     *
     * @param usedBytes      bytes currently occupied by live objects
     * @param committedBytes bytes reserved from the OS (may not all be in use)
     * @param maxBytes       JVM hard ceiling; -1 when no limit is set (common for non-heap)
     * @param usedFraction   used / max when max > 0, else used / committed
     * @param pools          per-pool breakdown (Eden, Old Gen, Metaspace, Code Cache, …)
     */
    public record RegionStats(
            long usedBytes,
            long committedBytes,
            long maxBytes,
            double usedFraction,
            Map<String, PoolStats> pools
    ) {
        public RegionStats(long used, long committed, long max, Map<String, PoolStats> pools) {
            this(used, committed, max,
                    max > 0 ? (double) used / max : (committed > 0 ? (double) used / committed : 0.0),
                    pools);
        }

        public long usedMb()      { return usedBytes      / MB; }
        public long committedMb() { return committedBytes  / MB; }
        public long maxMb()       { return maxBytes > 0 ? maxBytes / MB : -1; }
    }

    /**
     * Stats for a single memory pool (e.g. "G1 Eden Space", "Metaspace").
     *
     * @param maxBytes  -1 when the pool has no configured upper bound
     */
    public record PoolStats(long usedBytes, long committedBytes, long maxBytes) {
        public long usedMb() { return usedBytes / MB; }
        public long maxMb()  { return maxBytes > 0 ? maxBytes / MB : -1; }
    }

    /**
     * Thread counts from {@link ThreadMXBean}.
     *
     * Stack memory (栈) is not directly measurable via MXBeans; the OS-level cost is
     * approximately {@code live × -Xss} (default 512 KB on most JVM builds).
     */
    public record ThreadStats(int live, int daemon, int peak) {
        public int nonDaemon() { return live - daemon; }
    }

    /**
     * Aggregate GC counters split into young-gen (minor) and full (major/mixed) collections.
     *
     * Elevated fullCollections or high fullCollectionTimeMs relative to youngCollectionTimeMs
     * signals old-gen pressure — common when the embedding cache grows beyond the tuned old-gen
     * budget, or when Metaspace is under-provisioned on startup.
     */
    public record GcStats(
            long youngCollections,
            long youngCollectionTimeMs,
            long fullCollections,
            long fullCollectionTimeMs
    ) {}
}
