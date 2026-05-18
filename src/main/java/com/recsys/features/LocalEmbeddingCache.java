package com.recsys.features;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * JVM-heap cache (Tier 3) in front of a backing EmbeddingStore (Tier 2, typically Redis).
 *
 * Read-through: a cache miss fetches from the backing store and populates the cache.
 * Write-through: setEmbedding writes to both the backing store and the cache atomically.
 *
 * Item embeddings are written infrequently (only when a new model trains) and read on
 * every similarity-search request. The cache is bounded to prevent accidental writes
 * for arbitrary ids from growing heap until Full GC/OOM.
 *
 * Call warmUp() at startup to pre-populate the cache from Redis so the first requests
 * are served entirely from heap rather than paying a Redis round-trip.
 */
public class LocalEmbeddingCache implements EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingCache.class);
    private static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final EmbeddingStore backingStore;
    private final int maxEntries;
    private final ConcurrentHashMap<Integer, float[]> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Integer> evictionOrder = new ConcurrentLinkedQueue<>();

    public LocalEmbeddingCache(EmbeddingStore backingStore) {
        this(backingStore, readIntEnv("LOCAL_EMBEDDING_CACHE_MAX_ENTRIES", DEFAULT_MAX_ENTRIES));
    }

    LocalEmbeddingCache(EmbeddingStore backingStore, int maxEntries) {
        this.backingStore = backingStore;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * Bulk-loads all embeddings from the backing store into the heap cache.
     * Should be called once at server startup, after seeding Redis from the file system.
     */
    public void warmUp() {
        if (!(backingStore instanceof RedisEmbeddingStore redisStore)) return;
        Map<Integer, float[]> all = redisStore.loadAll();
        putAll(all);
        log.info("LocalEmbeddingCache warmed up with {} embeddings from Redis", all.size());
    }

    /**
     * Pre-loads embeddings directly from a file-system-sourced map (Tier 1 → Tier 3 shortcut).
     * Avoids a Redis round-trip when the classpath data is already available at startup.
     */
    public void preload(Map<Integer, float[]> embeddings) {
        putAll(embeddings);
        log.info("LocalEmbeddingCache preloaded {} embeddings from file system", embeddings.size());
    }

    @Override
    public float[] getEmbedding(int id) {
        float[] cached = cache.get(id);
        if (cached != null) return cached;

        float[] fromStore = backingStore.getEmbedding(id);
        if (fromStore != null) put(id, fromStore);
        return fromStore;
    }

    @Override
    public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();

        Map<Integer, float[]> result = new HashMap<>(ids.size() * 2);
        List<Integer> misses = new ArrayList<>();

        for (int id : ids) {
            float[] cached = cache.get(id);
            if (cached != null) {
                result.put(id, cached);
            } else {
                misses.add(id);
            }
        }

        if (!misses.isEmpty()) {
            Map<Integer, float[]> fetched = backingStore.getEmbeddings(misses);
            putAll(fetched);
            result.putAll(fetched);
        }

        return result;
    }

    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        backingStore.setEmbedding(id, vector, ttlSeconds);
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
        return cache.size();
    }

    public int maxEntries() {
        return maxEntries;
    }

    private void putAll(Map<Integer, float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) return;
        embeddings.forEach(this::put);
    }

    private void put(Integer id, float[] vector) {
        if (id == null || vector == null) return;
        if (cache.put(id, vector) == null) {
            evictionOrder.offer(id);
            evictOverflow();
        }
    }

    private void evictOverflow() {
        while (cache.size() > maxEntries) {
            Integer eldest = evictionOrder.poll();
            if (eldest == null) return;
            cache.remove(eldest);
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
