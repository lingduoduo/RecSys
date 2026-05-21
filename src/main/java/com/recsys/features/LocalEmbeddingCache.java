package com.recsys.features;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
public class LocalEmbeddingCache implements EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingCache.class);
    private static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final EmbeddingStore backingStore;
    private final int maxEntries;
    private final Map<Integer, float[]> cache;

    public LocalEmbeddingCache(EmbeddingStore backingStore) {
        this(backingStore, readIntEnv("LOCAL_EMBEDDING_CACHE_MAX_ENTRIES", DEFAULT_MAX_ENTRIES));
    }

    LocalEmbeddingCache(EmbeddingStore backingStore, int maxEntries) {
        this.backingStore = backingStore;
        this.maxEntries = Math.max(1, maxEntries);
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
        float[] cached;
        synchronized (cache) {
            cached = cache.get(id);
        }
        if (cached != null) return cached;

        float[] fromStore = backingStore.getEmbedding(id);
        if (fromStore != null) put(id, fromStore);
        return fromStore;
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

        if (!misses.isEmpty()) {
            Map<Integer, float[]> fetched = backingStore.getEmbeddings(new ArrayList<>(misses));
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
        synchronized (cache) {
            return cache.size();
        }
    }

    public int maxEntries() {
        return maxEntries;
    }

    private void putAll(Map<Integer, float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) return;
        synchronized (cache) {
            embeddings.forEach(this::putLocked);
        }
    }

    private void put(Integer id, float[] vector) {
        if (id == null || vector == null) return;
        synchronized (cache) {
            putLocked(id, vector);
        }
    }

    private void putLocked(Integer id, float[] vector) {
        if (id == null || vector == null) return;
        cache.put(id, vector);
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
