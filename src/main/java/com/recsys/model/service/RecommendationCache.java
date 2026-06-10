package com.recsys.model.service;

import com.recsys.config.RecommendationCacheProperties;
import com.recsys.model.dto.ScoredItem;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

class RecommendationCache {

    private final boolean enabled;
    private final boolean coldStartEnabled;
    private final int coldStartMaxK;
    private final TtlLruCache<RecommendationKey, List<ScoredItem>> recommendations;
    private final TtlLruCache<ColdStartKey, List<ScoredItem>> coldStarts;

    RecommendationCache(RecommendationCacheProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RecommendationCache(RecommendationCacheProperties properties, Clock clock) {
        RecommendationCacheProperties props = properties == null ? new RecommendationCacheProperties() : properties;
        this.enabled = props.isEnabled();
        this.coldStartEnabled = props.isColdStartEnabled();
        this.coldStartMaxK = props.getColdStartMaxK();
        this.recommendations = new TtlLruCache<>(
                props.getMaxEntries(),
                props.getTtlSeconds(),
                clock,
                props.getComputeWaitTimeoutMillis());
        // Cold-start entries are variant+model-version scoped — they only change on deploy,
        // so a much longer TTL avoids re-computing the pool on every per-user TTL cycle.
        this.coldStarts = new TtlLruCache<>(
                Math.max(1, props.getMaxEntries() / 20),
                props.getColdStartTtlSeconds(),
                clock,
                props.getComputeWaitTimeoutMillis());
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isColdStartEnabled() {
        return enabled && coldStartEnabled;
    }

    int coldStartMaxK() {
        return coldStartMaxK;
    }

    List<ScoredItem> get(RecommendationKey key) {
        return enabled ? recommendations.get(key) : null;
    }

    void put(RecommendationKey key, List<ScoredItem> items) {
        if (enabled) {
            recommendations.put(key, List.copyOf(items));
        }
    }

    /**
     * Returns a cached result or, on miss, runs {@code loader} exactly once even under concurrent
     * requests for the same key (thundering-herd protection via in-flight deduplication).
     */
    List<ScoredItem> getOrCompute(RecommendationKey key, Supplier<List<ScoredItem>> loader) {
        if (!enabled) return loader.get();
        return recommendations.getOrCompute(key, () -> List.copyOf(loader.get()));
    }

    List<ScoredItem> getColdStart(ColdStartKey key) {
        return isColdStartEnabled() ? coldStarts.get(key) : null;
    }

    void putColdStart(ColdStartKey key, List<ScoredItem> items) {
        if (isColdStartEnabled()) {
            coldStarts.put(key, List.copyOf(items));
        }
    }

    /**
     * Same deduplication guarantee as {@link #getOrCompute} but for the shared cold-start pool.
     */
    List<ScoredItem> getOrComputeColdStart(ColdStartKey key, Supplier<List<ScoredItem>> loader) {
        if (!isColdStartEnabled()) return loader.get();
        return coldStarts.getOrCompute(key, () -> List.copyOf(loader.get()));
    }

    CacheStats recommendationStats() {
        return recommendations.stats();
    }

    CacheStats coldStartStats() {
        return coldStarts.stats();
    }

    record CacheStats(long hits, long misses) {
        double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }

        @Override
        public String toString() {
            return String.format("hits=%d misses=%d hitRate=%.2f", hits, misses, hitRate());
        }
    }

    record RecommendationKey(
            String userId,
            int k,
            List<String> excludeItemIds,
            String variant,
            String modelVersion
    ) {
        RecommendationKey {
            excludeItemIds = List.copyOf(excludeItemIds);
        }
    }

    record ColdStartKey(String variant, String modelVersion) {
    }

    private static final class TtlLruCache<K, V> {
        private final int maxEntries;
        private final long ttlMillis;
        private final Clock clock;
        private final LinkedHashMap<K, Entry<V>> entries;
        // Deduplicates concurrent misses for the same key so only one thread computes.
        private final ConcurrentHashMap<K, CompletableFuture<V>> inflight = new ConcurrentHashMap<>();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private long computeWaitTimeoutMillis;
        // Insertion-order LinkedHashMap: get() does NOT structurally modify the map,
        // so concurrent reads can share a read lock instead of serialising on a write lock.
        // This eliminates the synchronized-on-every-read bottleneck from access-order LRU.
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Lock readLock  = rwLock.readLock();
        private final Lock writeLock = rwLock.writeLock();

        private TtlLruCache(int maxEntries, int ttlSeconds, Clock clock, long computeWaitTimeoutMillis) {
            this.maxEntries = Math.max(1, maxEntries);
            this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
            this.clock = clock;
            this.computeWaitTimeoutMillis = Math.max(1L, computeWaitTimeoutMillis);
            // false = insertion-order: reads are non-structural, allowing shared read locks.
            this.entries = new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                    return size() > TtlLruCache.this.maxEntries;
                }
            };
        }

        V get(K key) {
            long now = clock.millis();
            readLock.lock();
            try {
                Entry<V> entry = entries.get(key);
                if (entry == null) {
                    misses.incrementAndGet();
                    return null;
                }
                if (entry.expiresAtMillis > now) {
                    hits.incrementAndGet();
                    return entry.value;
                }
            } finally {
                readLock.unlock();
            }
            // Entry is expired: promote to write lock for removal (double-checked).
            writeLock.lock();
            try {
                Entry<V> entry = entries.get(key);
                if (entry != null && entry.expiresAtMillis <= now) {
                    entries.remove(key);
                }
            } finally {
                writeLock.unlock();
            }
            misses.incrementAndGet();
            return null;
        }

        void put(K key, V value) {
            writeLock.lock();
            try {
                entries.put(key, new Entry<>(value, clock.millis() + ttlMillis));
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Returns a cached value or computes it via {@code loader}, ensuring that concurrent
         * callers for the same key share a single in-flight {@link CompletableFuture} rather than
         * each running the (potentially expensive) loader independently.
         */
        V getOrCompute(K key, Supplier<V> loader) {
            V cached = get(key);
            if (cached != null) return cached;

            CompletableFuture<V> newFuture = new CompletableFuture<>();
            CompletableFuture<V> existing = inflight.putIfAbsent(key, newFuture);

            if (existing == null) {
                // This thread owns the computation.
                try {
                    V value = loader.get();
                    put(key, value);
                    newFuture.complete(value);
                    return value;
                } catch (RuntimeException ex) {
                    newFuture.completeExceptionally(ex);
                    throw ex;
                } finally {
                    inflight.remove(key, newFuture);
                }
            }

            // Another thread is already computing; wait for its result.
            try {
                return existing.get(computeWaitTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                throw new IllegalStateException(
                        "Timed out waiting for in-flight recommendation cache computation", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for recommendation cache computation", ex);
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            } catch (java.util.concurrent.ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            }
        }

        RecommendationCache.CacheStats stats() {
            return new RecommendationCache.CacheStats(hits.get(), misses.get());
        }

        private record Entry<V>(V value, long expiresAtMillis) {
        }
    }
}
