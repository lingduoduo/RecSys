package com.recsys.infrastructure.redis;

import com.recsys.infrastructure.resilience.HotKeyDetector;
import com.recsys.infrastructure.store.TrendingStore;
import io.lettuce.core.ScriptOutputType;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trending top-K reads, served from a short-lived JVM cache in front of Redis.
 *
 * Problem: a handful of trending-window keys ({@code topk:last_hour}, {@code topk:last_day},
 * etc.) are read on every recommendation request across all JVM instances.
 *
 * What protects them, in order:
 *   1. Local JVM cache — ConcurrentHashMap keyed by window, 2-second fresh TTL, which
 *      absorbs the vast majority of reads.
 *   2. Per-window singleflight — on a cache miss only the first thread in this JVM
 *      fetches; the rest wait on its result.
 *   3. Serve-stale — a Redis failure within the 60-second stale window returns the last
 *      known value rather than propagating the error.
 *
 * Read path on a cache miss: evaluate {@link #READ_CANONICAL_SNAPSHOT} against the
 * canonical snapshot written atomically by the Flink job ({@code topk:{window}:value},
 * guarded by {@code topk:{window}:version}). If no canonical snapshot exists — a cold
 * Redis before Flink's first write — fall back to the unversioned {@code topk:<window>}
 * key and count it in {@link #legacyFallbackFetches()}.
 *
 * <p>This class previously fanned each window out to N identical replica keys
 * ({@code topk:<window>:s0..sN}) to spread hot-key read QPS. That machinery was removed
 * on 2026-07-28: nothing had written those keys since the canonical snapshot path landed
 * in {@code 01870d2}, so every read already resolved via the canonical key. See
 * {@code docs/superpowers/specs/2026-07-28-kv-store-sharp-edges-design.md}.
 *
 * Hit-rate metrics and {@link HotKeyDetector} integration expose which windows are
 * hottest and how effectively the local cache is absorbing load.
 */
public final class ShardedTopKStore implements TrendingStore {

    private static final String READ_CANONICAL_SNAPSHOT = """
            if not redis.call('GET', KEYS[2]) then return {'absent'} end
            local ids = redis.call('ZREVRANGE', KEYS[1], 0, tonumber(ARGV[1]))
            table.insert(ids, 1, 'canonical')
            return ids
            """;

    static final long DEFAULT_CACHE_TTL_MS     = 2_000L;
    static final long DEFAULT_STALE_TTL_MS     = 60_000L;
    static final int  MAX_FULL_CACHE_SIZE      = 100;
    private static final long FETCH_WAIT_TIMEOUT_MS = 2_000L;

    private final RedisExecutor writeExec;
    private final RedisExecutor readExec;
    private final String keyPrefix;   // e.g. "topk:"
    private final long cacheTtlMs;
    private final long staleTtlMs;

    // Local hot-data cache: window → CachedIds.
    private final ConcurrentHashMap<String, CachedIds> hotCache   = new ConcurrentHashMap<>();
    // Singleflight: deduplicates concurrent cache misses within this JVM.
    private final ConcurrentHashMap<String, CompletableFuture<CachedIds>> inflight = new ConcurrentHashMap<>();

    // Metrics
    private final AtomicLong localHits   = new AtomicLong();
    private final AtomicLong redisFetches = new AtomicLong();
    private final AtomicLong legacyFallbackFetches = new AtomicLong();

    private final HotKeyDetector hotKeyDetector;

    /**
     * Single-executor constructor — reads and writes use the same Redis connection.
     * This is what all three production call sites use.
     */
    public ShardedTopKStore(RedisExecutor exec, String keyPrefix) {
        this(exec, exec, keyPrefix,
                readLongEnv("ONLINE_TOPK_CACHE_TTL_MS", DEFAULT_CACHE_TTL_MS),
                readLongEnv("ONLINE_TOPK_STALE_TTL_MS", DEFAULT_STALE_TTL_MS), new HotKeyDetector());
    }

    /**
     * AZ-aware constructor — primary-only reads ({@link #getTopKIdsPrimary}) go to
     * {@code writeExec}, cached reads go to {@code readExec} (an AZ-local replica).
     */
    public ShardedTopKStore(RedisExecutor writeExec, RedisExecutor readExec, String keyPrefix) {
        this(writeExec, readExec, keyPrefix,
                readLongEnv("ONLINE_TOPK_CACHE_TTL_MS", DEFAULT_CACHE_TTL_MS),
                readLongEnv("ONLINE_TOPK_STALE_TTL_MS", DEFAULT_STALE_TTL_MS), new HotKeyDetector());
    }

    ShardedTopKStore(RedisExecutor writeExec, RedisExecutor readExec, String keyPrefix,
                     long cacheTtlMs, HotKeyDetector hotKeyDetector) {
        this(writeExec, readExec, keyPrefix, cacheTtlMs, DEFAULT_STALE_TTL_MS, hotKeyDetector);
    }

    ShardedTopKStore(RedisExecutor writeExec, RedisExecutor readExec, String keyPrefix,
                     long cacheTtlMs, long staleTtlMs, HotKeyDetector hotKeyDetector) {
        this.writeExec      = writeExec;
        this.readExec       = readExec;
        this.keyPrefix      = keyPrefix;
        this.cacheTtlMs     = Math.max(0L, cacheTtlMs);
        this.staleTtlMs     = Math.max(this.cacheTtlMs, staleTtlMs);
        this.hotKeyDetector = hotKeyDetector;
    }

    // ── Read path ──────────────────────────────────────────────────────────────────

    /**
     * Returns the top {@code k} IDs for {@code window}, served from the local JVM cache
     * or from Redis on cache miss.
     */
    @Override
    public List<String> getTopKIds(String window, int k) {
        if (k <= 0) return List.of();
        hotKeyDetector.record(window);

        long now = System.currentTimeMillis();
        CachedIds cached = hotCache.get(window);
        if (cached != null && cached.expiresAtMs > now) {
            localHits.incrementAndGet();
            return slice(cached.ids, k);
        }

        // Singleflight: only the first thread in this JVM fetches from Redis.
        CompletableFuture<CachedIds> myFuture = new CompletableFuture<>();
        CompletableFuture<CachedIds> existing = inflight.putIfAbsent(window, myFuture);

        if (existing == null) {
            try {
                CachedIds fresh = fetchFromRedis(window, k, now);
                myFuture.complete(fresh);
                return slice(fresh.ids, k);
            } catch (RuntimeException ex) {
                if (cached != null && cached.staleExpiresAtMs > now) {
                    myFuture.complete(cached);
                    return slice(cached.ids, k);
                }
                myFuture.completeExceptionally(ex);
                throw ex;
            } finally {
                inflight.remove(window, myFuture);
            }
        }

        try {
            return slice(existing.get(FETCH_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS).ids, k);
        } catch (TimeoutException | InterruptedException | ExecutionException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            // Fail-open: fetch independently rather than returning empty.
            return slice(fetchFromRedis(window, k, now).ids, k);
        }
    }

    @Override
    public List<String> getTopKIdsPrimary(String window, int k) {
        if (k <= 0) return List.of();
        List<String> canonical = writeExec.executePrimaryRead(c -> {
            CanonicalSnapshot snapshot = readCanonicalSnapshot(c, window, k);
            return snapshot.present ? snapshot.ids : c.zrevrange(legacyKey(window), 0, k - 1);
        });
        return canonical == null ? List.of() : List.copyOf(canonical);
    }

    private CachedIds fetchFromRedis(String window, int k, long now) {
        int fetchSize = Math.max(k, MAX_FULL_CACHE_SIZE);

        return readExec.executeRead(c -> {
            CanonicalSnapshot snapshot = readCanonicalSnapshot(c, window, fetchSize);
            List<String> ids = snapshot.ids;
            if (!snapshot.present) {
                // Cold Redis, before the Flink job's first canonical write.
                List<String> oldIds = c.zrevrange(legacyKey(window), 0, fetchSize - 1);
                ids = oldIds == null ? List.of() : List.copyOf(oldIds);
                if (!ids.isEmpty()) legacyFallbackFetches.incrementAndGet();
            }
            redisFetches.incrementAndGet();
            CachedIds result = new CachedIds(ids, now + cacheTtlMs, now + staleTtlMs);
            if (staleTtlMs > 0L) hotCache.put(window, result);
            return result;
        });
    }

    private String canonicalKey(String window) {
        return keyPrefix + "{" + window + "}:value";
    }

    private String canonicalVersionKey(String window) {
        return keyPrefix + "{" + window + "}:version";
    }

    private CanonicalSnapshot readCanonicalSnapshot(
            io.lettuce.core.api.sync.RedisCommands<String, String> commands, String window, int limit) {
        List<Object> raw = commands.eval(READ_CANONICAL_SNAPSHOT, ScriptOutputType.MULTI,
                new String[]{canonicalKey(window), canonicalVersionKey(window)},
                Integer.toString(limit - 1));
        if (raw == null || raw.isEmpty() || !"canonical".equals(String.valueOf(raw.get(0)))) {
            return new CanonicalSnapshot(false, List.of());
        }
        return new CanonicalSnapshot(true, raw.subList(1, raw.size()).stream()
                .map(String::valueOf).toList());
    }

    // ── Accessors & diagnostics ───────────────────────────────────────────────────

    /** Number of entries currently in the local JVM cache. */
    public int hotCacheSize() { return hotCache.size(); }

    /** Cumulative local (JVM) cache hits since construction. */
    public long localHits() { return localHits.get(); }

    /** Cumulative Redis fetches since construction. */
    public long redisFetches() { return redisFetches.get(); }

    /**
     * Cumulative reads served from the legacy unsharded key because no canonical snapshot
     * existed. A non-zero value means the Flink job has not written {@code topk:{window}:value}.
     */
    public long legacyFallbackFetches() { return legacyFallbackFetches.get(); }

    /** Local cache hit rate: {@code localHits / (localHits + redisFetches)}. */
    public double localHitRate() {
        long h = localHits.get(), r = redisFetches.get();
        return (h + r) == 0 ? 0.0 : (double) h / (h + r);
    }

    /** Exposes the embedded hot-key detector for monitoring which windows are hottest. */
    public HotKeyDetector hotKeyDetector() { return hotKeyDetector; }

    // ── Internal ──────────────────────────────────────────────────────────────────

    private static List<String> slice(List<String> ids, int k) {
        return ids.size() <= k ? ids : ids.subList(0, k);
    }

    private String legacyKey(String window) {
        return keyPrefix + window;
    }

    static long readLongEnv(String envName, long defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private record CachedIds(List<String> ids, long expiresAtMs, long staleExpiresAtMs) {}
    private record CanonicalSnapshot(boolean present, List<String> ids) {}
}
