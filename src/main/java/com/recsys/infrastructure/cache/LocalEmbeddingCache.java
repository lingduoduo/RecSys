package com.recsys.infrastructure.cache;

import com.recsys.infrastructure.BloomFilterGuard;
import com.recsys.infrastructure.SingleFlight;
import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-heap cache (Tier 3) in front of a backing EmbeddingStore (Tier 2, typically Redis).
 *
 * Read-through: a cache miss fetches from the backing store and populates the cache.
 * Write-through: setEmbedding writes to both the backing store and the cache atomically.
 *
 * Item embeddings are written infrequently (only when a new model trains) and read on
 * every similarity-search request. The cache is bounded with access-order LRU eviction
 * to prevent arbitrary ids from growing heap until Full GC/OOM.
 *
 * Call warmUp() at startup to pre-populate the cache from Redis so the first requests
 * are served entirely from heap rather than paying a Redis round-trip.
 *
 * Cache penetration protection:
 *  - Bloom filter: after warmUp/preload populates the filter with all valid IDs, requests
 *    for IDs definitively absent (mightContain = false) are short-circuited without a
 *    Redis round-trip.  The filter is inactive until a bulk load runs.
 *  - Null sentinel: absent IDs confirmed by the backing store are cached for
 *    NULL_SENTINEL_TTL_MS so that repeated queries for the same absent ID do not each
 *    trigger a backing-store round-trip.
 *
 * Multi-level cache (cache avalanche mitigation):
 *  Tier-1 (file system) → Tier-2 (Redis via backing store) → Tier-3 (this JVM heap).
 *  A Redis outage degrades gracefully: this cache continues serving hot embeddings
 *  from heap; only cold misses fall through to the unavailable tier.
 */
public class LocalEmbeddingCache implements EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingCache.class);
    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    // Short null-sentinel TTL: absorbs burst penetration without staling too long.
    private static final long NULL_SENTINEL_TTL_MS = 30_000L;
    private static final long MISS_WAIT_TIMEOUT_MS = 2_000L;

    private final EmbeddingStore backingStore;
    private final int maxEntries;
    private final Map<Integer, float[]> cache;

    // Cache penetration guard — Bloom filter: activated after warmUp/preload loads all valid IDs.
    private final BloomFilterGuard bloom;
    private volatile boolean bloomPopulated = false;

    // Cache penetration guard — Null sentinel: maps absent ID → expiry timestamp.
    private final ConcurrentHashMap<Integer, Long> nullSentinels = new ConcurrentHashMap<>();
    private final SingleFlight<Integer, float[]> missSingleFlight = new SingleFlight<>(MISS_WAIT_TIMEOUT_MS);

    public LocalEmbeddingCache(EmbeddingStore backingStore) {
        this(backingStore, readIntEnv("LOCAL_EMBEDDING_CACHE_MAX_ENTRIES", DEFAULT_MAX_ENTRIES));
    }

    LocalEmbeddingCache(EmbeddingStore backingStore, int maxEntries) {
        this(backingStore, maxEntries, new BloomFilterGuard(Math.max(1, maxEntries) * 2, 0.01));
    }

    LocalEmbeddingCache(EmbeddingStore backingStore, int maxEntries, BloomFilterGuard bloom) {
        this.backingStore = backingStore;
        this.maxEntries = Math.max(1, maxEntries);
        this.bloom = bloom;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, float[]> eldest) {
                return size() > LocalEmbeddingCache.this.maxEntries;
            }
        });
    }

    /**
     * Bulk-loads all embeddings from the backing store into the heap cache.
     * Should be called once at server startup, after seeding Redis from the file system.
     * Also activates Bloom filter protection against cache penetration.
     */
    public void warmUp() {
        if (!(backingStore instanceof RedisEmbeddingStore redisStore)) return;
        Map<Integer, float[]> all = redisStore.loadAll();
        if (!all.isEmpty()) {
            populateAll(all);
            bloomPopulated = true;
        }
        log.info("LocalEmbeddingCache warmed up with {} embeddings from Redis", all.size());
    }

    /**
     * Pre-loads embeddings directly from a file-system-sourced map (Tier 1 → Tier 3 shortcut).
     * Avoids a Redis round-trip when the classpath data is already available at startup.
     * Also activates Bloom filter protection against cache penetration.
     */
    public void preload(Map<Integer, float[]> embeddings) {
        if (embeddings != null && !embeddings.isEmpty()) {
            populateAll(embeddings);
            bloomPopulated = true;
        }
        log.info("LocalEmbeddingCache preloaded {} embeddings from file system",
                embeddings == null ? 0 : embeddings.size());
    }

    @Override
    public float[] getEmbedding(int id) {
        // 1. Bloom filter guard (active only after warm-up to ensure completeness).
        if (bloomPopulated && !bloom.mightContain(id)) return null;

        // 2. Null sentinel: skip backing store for recently confirmed absent IDs.
        Long nullExpiry = nullSentinels.get(id);
        if (nullExpiry != null && nullExpiry > System.currentTimeMillis()) return null;

        float[] cached;
        synchronized (cache) {
            cached = cache.get(id);
        }
        if (cached != null) return cached;

        return missSingleFlight.execute(id, () -> loadMissingEmbedding(id));
    }

    @Override
    public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();

        Map<Integer, float[]> result = new HashMap<>(ids.size() * 2);
        Set<Integer> misses = new LinkedHashSet<>();

        synchronized (cache) {
            for (int id : ids) {
                float[] cached = cache.get(id);
                if (cached != null) {
                    result.put(id, cached);
                } else {
                    misses.add(id);
                }
            }
        }

        if (misses.isEmpty()) return result;

        // Apply penetration guards to the miss set (outside sync block to reduce contention).
        long now = System.currentTimeMillis();
        Set<Integer> toFetch = new LinkedHashSet<>();
        for (int id : misses) {
            if (bloomPopulated && !bloom.mightContain(id)) continue;
            Long nullExpiry = nullSentinels.get(id);
            if (nullExpiry != null && nullExpiry > now) continue;
            toFetch.add(id);
        }

        if (!toFetch.isEmpty()) {
            Map<Integer, float[]> fetched = backingStore.getEmbeddings(toFetch);
            for (int id : toFetch) {
                if (fetched.containsKey(id)) {
                    bloom.add(id);
                } else {
                    nullSentinels.put(id, now + NULL_SENTINEL_TTL_MS);
                }
            }
            putAll(fetched);
            result.putAll(fetched);
        }

        return result;
    }

    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        backingStore.setEmbedding(id, vector, ttlSeconds);
        bloom.add(id);
        nullSentinels.remove(id);
        put(id, vector);
    }

    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        backingStore.setEmbeddings(vectors, ttlSeconds);
        putAll(vectors);
    }

    @Override
    public Set<Integer> scanIds(int maxKeys) {
        return backingStore.scanIds(maxKeys);
    }

    public int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    public int maxEntries() {
        return maxEntries;
    }

    boolean isBloomPopulated() { return bloomPopulated; }

    int inflightMisses() { return missSingleFlight.inflightCount(); }

    private float[] loadMissingEmbedding(int id) {
        Long nullExpiry = nullSentinels.get(id);
        long now = System.currentTimeMillis();
        if (nullExpiry != null && nullExpiry > now) return null;

        synchronized (cache) {
            float[] cached = cache.get(id);
            if (cached != null) return cached;
        }

        float[] fromStore = backingStore.getEmbedding(id);
        if (fromStore != null) {
            bloom.add(id);
            put(id, fromStore);
        } else {
            nullSentinels.put(id, now + NULL_SENTINEL_TTL_MS);
        }
        return fromStore;
    }

    // Bulk put without activating bloomPopulated (used by setEmbeddings — partial write).
    private void putAll(Map<Integer, float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) return;
        synchronized (cache) {
            embeddings.forEach((id, vec) -> {
                if (id != null && vec != null) {
                    cache.put(id, vec);
                    bloom.add(id);
                    nullSentinels.remove(id);
                }
            });
        }
    }

    // Bulk put for a complete snapshot (warmUp/preload); caller sets bloomPopulated.
    private void populateAll(Map<Integer, float[]> embeddings) {
        putAll(embeddings);
    }

    private void put(Integer id, float[] vector) {
        if (id == null || vector == null) return;
        synchronized (cache) {
            cache.put(id, vector);
        }
    }

    private static int readIntEnv(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
