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

/**
 * JVM-heap cache (Tier 3) in front of a backing EmbeddingStore (Tier 2, typically Redis).
 *
 * Read-through: a cache miss fetches from the backing store and populates the cache.
 * Write-through: setEmbedding writes to both the backing store and the cache atomically.
 *
 * Item embeddings are written infrequently (only when a new model trains) and read on
 * every similarity-search request, so a simple unbounded ConcurrentHashMap is appropriate —
 * the total size is bounded by the item catalog (typically tens of thousands of float[]).
 *
 * Call warmUp() at startup to pre-populate the cache from Redis so the first requests
 * are served entirely from heap rather than paying a Redis round-trip.
 */
public class LocalEmbeddingCache implements EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingCache.class);

    private final EmbeddingStore backingStore;
    private final ConcurrentHashMap<Integer, float[]> cache = new ConcurrentHashMap<>();

    public LocalEmbeddingCache(EmbeddingStore backingStore) {
        this.backingStore = backingStore;
    }

    /**
     * Bulk-loads all embeddings from the backing store into the heap cache.
     * Should be called once at server startup, after seeding Redis from the file system.
     */
    public void warmUp() {
        if (!(backingStore instanceof RedisEmbeddingStore redisStore)) return;
        Map<Integer, float[]> all = redisStore.loadAll();
        cache.putAll(all);
        log.info("LocalEmbeddingCache warmed up with {} embeddings from Redis", all.size());
    }

    /**
     * Pre-loads embeddings directly from a file-system-sourced map (Tier 1 → Tier 3 shortcut).
     * Avoids a Redis round-trip when the classpath data is already available at startup.
     */
    public void preload(Map<Integer, float[]> embeddings) {
        cache.putAll(embeddings);
        log.info("LocalEmbeddingCache preloaded {} embeddings from file system", embeddings.size());
    }

    @Override
    public float[] getEmbedding(int id) {
        float[] cached = cache.get(id);
        if (cached != null) return cached;

        float[] fromStore = backingStore.getEmbedding(id);
        if (fromStore != null) cache.put(id, fromStore);
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
            cache.putAll(fetched);
            result.putAll(fetched);
        }

        return result;
    }

    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        backingStore.setEmbedding(id, vector, ttlSeconds);
        cache.put(id, vector);
    }

    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        backingStore.setEmbeddings(vectors, ttlSeconds);
        cache.putAll(vectors);
    }

    @Override
    public Set<Integer> scanIds(int maxKeys) {
        return backingStore.scanIds(maxKeys);
    }

    public int cacheSize() {
        return cache.size();
    }
}
