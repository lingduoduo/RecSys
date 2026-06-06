package com.recsys.infrastructure.redis;

import com.recsys.infrastructure.HotKeyDetector;
import com.recsys.streaming.TrendingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-replica sharding for Redis top-K sorted-set keys (热点Key分片).
 *
 * Problem: a handful of trending-window keys ({@code topk:last_hour}, {@code topk:last_day},
 * etc.) are read on every recommendation request across all JVM instances.  When the
 * 2-second JVM cache expires all instances simultaneously rush to Redis, saturating a
 * single key (and its hash-slot owner in a Redis Cluster).
 *
 * Solution — N read replicas per window:
 *   Physical keys: {@code {prefix}{window}:s0}, {@code :s1}, …, {@code :s(N-1)}
 *   All shards hold identical data; each is written by {@link #seedAllShards}.
 *   On a JVM-cache miss, one shard is chosen at random → N-fold reduction in per-key QPS.
 *
 * Local JVM cache (热点本地缓存):
 *   Identical to {@link RedisTopKStore}: ConcurrentHashMap keyed by window, 2-second TTL,
 *   per-window singleflight deduplication.  The local cache absorbs the vast majority of
 *   reads; sharding only affects the infrequent Redis refreshes.
 *
 * Hit-rate metrics and {@link HotKeyDetector} integration expose which windows are
 * hottest and how effectively the local cache is absorbing load.
 */
public final class ShardedTopKStore implements TrendingStore {

    private static final Logger log = LoggerFactory.getLogger(ShardedTopKStore.class);

    static final long DEFAULT_CACHE_TTL_MS     = 2_000L;
    static final long DEFAULT_STALE_TTL_MS     = 60_000L;
    static final int  DEFAULT_SHARD_COUNT      = 4;
    static final int  MAX_FULL_CACHE_SIZE      = 100;
    private static final long FETCH_WAIT_TIMEOUT_MS = 2_000L;

    private final Pool<Jedis> writePool;
    private final Pool<Jedis> readPool;
    private final String keyPrefix;   // e.g. "topk:"
    private final int shardCount;
    private final long cacheTtlMs;
    private final long staleTtlMs;

    // Local hot-data cache: window → CachedIds.  Keyed by logical window, not by shard.
    private final ConcurrentHashMap<String, CachedIds> hotCache   = new ConcurrentHashMap<>();
    // Singleflight: deduplicates concurrent cache misses within this JVM.
    private final ConcurrentHashMap<String, CompletableFuture<CachedIds>> inflight = new ConcurrentHashMap<>();

    // Metrics
    private final AtomicLong localHits   = new AtomicLong();
    private final AtomicLong redisFetches = new AtomicLong();
    private final AtomicLong legacyFallbackFetches = new AtomicLong();

    private final HotKeyDetector hotKeyDetector;

    /**
     * Single-pool constructor — reads and writes use the same Redis connection.
     * Preserved for backwards compatibility and non-replicated deployments.
     */
    public ShardedTopKStore(Pool<Jedis> pool, String keyPrefix) {
        this(pool, pool, keyPrefix, DEFAULT_SHARD_COUNT, DEFAULT_CACHE_TTL_MS,
                readLongEnv("ONLINE_TOPK_STALE_TTL_MS", DEFAULT_STALE_TTL_MS), new HotKeyDetector());
    }

    /**
     * AZ-aware constructor — writes (seedAllShards) go to {@code writePool}
     * (primary), reads (getTopKIds) go to {@code readPool} (AZ-local replica).
     */
    public ShardedTopKStore(Pool<Jedis> writePool, Pool<Jedis> readPool, String keyPrefix) {
        this(writePool, readPool, keyPrefix, DEFAULT_SHARD_COUNT, DEFAULT_CACHE_TTL_MS,
                readLongEnv("ONLINE_TOPK_STALE_TTL_MS", DEFAULT_STALE_TTL_MS), new HotKeyDetector());
    }

    ShardedTopKStore(Pool<Jedis> writePool, Pool<Jedis> readPool, String keyPrefix,
                     int shardCount, long cacheTtlMs, HotKeyDetector hotKeyDetector) {
        this(writePool, readPool, keyPrefix, shardCount, cacheTtlMs,
                DEFAULT_STALE_TTL_MS, hotKeyDetector);
    }

    ShardedTopKStore(Pool<Jedis> writePool, Pool<Jedis> readPool, String keyPrefix,
                     int shardCount, long cacheTtlMs, long staleTtlMs,
                     HotKeyDetector hotKeyDetector) {
        this.writePool      = writePool;
        this.readPool       = readPool;
        this.keyPrefix      = keyPrefix;
        this.shardCount     = Math.max(1, shardCount);
        this.cacheTtlMs     = Math.max(0L, cacheTtlMs);
        this.staleTtlMs     = Math.max(this.cacheTtlMs, staleTtlMs);
        this.hotKeyDetector = hotKeyDetector;
    }

    // ── Read path ──────────────────────────────────────────────────────────────────

    /**
     * Returns the top {@code k} IDs for {@code window}, served from the local JVM cache
     * or from a randomly selected Redis shard on cache miss.
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
                CachedIds fresh = fetchFromRandomShard(window, k, now);
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
            return slice(fetchFromRandomShard(window, k, now).ids, k);
        }
    }

    private CachedIds fetchFromRandomShard(String window, int k, long now) {
        // Random shard selection: spreads Redis read load across N keys/hash-slots.
        int shard = ThreadLocalRandom.current().nextInt(shardCount);
        String key = shardKey(window, shard);
        int fetchSize = Math.max(k, MAX_FULL_CACHE_SIZE);

        try (Jedis jedis = readPool.getResource()) {
            List<String> ids = List.copyOf(jedis.zrevrange(key, 0, fetchSize - 1));
            if (ids.isEmpty()) {
                List<String> legacyIds = List.copyOf(jedis.zrevrange(legacyKey(window), 0, fetchSize - 1));
                if (!legacyIds.isEmpty()) {
                    legacyFallbackFetches.incrementAndGet();
                    ids = legacyIds;
                }
            }
            redisFetches.incrementAndGet();
            CachedIds result = new CachedIds(ids, now + cacheTtlMs, now + staleTtlMs);
            if (staleTtlMs > 0L) hotCache.put(window, result);
            return result;
        }
    }

    // ── Write path (fan-out to all shards) ────────────────────────────────────────

    /**
     * Fan-out write: stores {@code memberScores} in all N shard keys so every shard
     * returns consistent data.  Typically called by Flink or a scheduled sync job
     * when trending data is refreshed.
     *
     * Invalidates the local JVM cache so the next read picks up fresh data.
     *
     * @param window       the window name, e.g. {@code "last_hour"}
     * @param memberScores map of {@code memberId → score} (higher score = higher rank)
     */
    public void seedAllShards(String window, Map<String, Double> memberScores) {
        if (memberScores == null || memberScores.isEmpty()) return;
        for (int shard = 0; shard < shardCount; shard++) {
            String key = shardKey(window, shard);
            try (Jedis jedis = writePool.getResource()) {
                jedis.zadd(key, memberScores);
            } catch (Exception e) {
                log.warn("Failed to seed shard {} for window {}: {}", shard, window, e.toString());
            }
        }
        try (Jedis jedis = writePool.getResource()) {
            jedis.zadd(legacyKey(window), memberScores);
        } catch (Exception e) {
            log.warn("Failed to seed legacy top-K key for window {}: {}", window, e.toString());
        }
        hotCache.remove(window); // invalidate so next read reflects new data
    }

    // ── Accessors & diagnostics ───────────────────────────────────────────────────

    /** Physical Redis key for a given logical window and shard index. */
    public String shardKey(String window, int shard) {
        return keyPrefix + window + ":s" + shard;
    }

    /** Number of entries currently in the local JVM cache. */
    public int hotCacheSize() { return hotCache.size(); }

    /** Cumulative local (JVM) cache hits since construction. */
    public long localHits() { return localHits.get(); }

    /** Cumulative Redis fetches (shard reads) since construction. */
    public long redisFetches() { return redisFetches.get(); }

    /** Cumulative reads served from the legacy unsharded key because a shard was empty. */
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

    private static long readLongEnv(String envName, long defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private record CachedIds(List<String> ids, long expiresAtMs, long staleExpiresAtMs) {}
}
