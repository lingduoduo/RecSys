package com.recsys.features;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Deduplicates concurrent computations for the same key (singleflight pattern, 缓存击穿防护).
 *
 * When multiple threads request the same key simultaneously during a cache miss, only the
 * first thread runs the {@link #execute supplier}; the rest wait for and share its result.
 * This prevents cache breakdown (缓存击穿) where N threads would all independently hit the
 * backing store for the same expired hot key.
 *
 * On timeout or interruption, the waiting thread falls back to computing independently
 * (fail-open) rather than blocking indefinitely.
 *
 * The same pattern is used inline in {@code RecommendationCache} and
 * {@code OnlineFeatureStore} — this class makes the pattern reusable and testable.
 *
 * @param <K> cache key type
 * @param <V> value type
 */
public final class SingleFlight<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> inflight = new ConcurrentHashMap<>();
    private final long waitTimeoutMs;

    public SingleFlight(long waitTimeoutMs) {
        this.waitTimeoutMs = Math.max(1L, waitTimeoutMs);
    }

    /**
     * Executes {@code supplier} for {@code key} at most once across concurrent callers.
     * Concurrent callers for the same key share the result of the first computation.
     * Falls back to an independent computation if the wait times out.
     */
    public V execute(K key, Supplier<V> supplier) {
        CompletableFuture<V> myFuture = new CompletableFuture<>();
        CompletableFuture<V> existing = inflight.putIfAbsent(key, myFuture);

        if (existing == null) {
            // This thread owns the computation.
            try {
                V value = supplier.get();
                myFuture.complete(value);
                return value;
            } catch (RuntimeException ex) {
                myFuture.completeExceptionally(ex);
                throw ex;
            } finally {
                inflight.remove(key, myFuture);
            }
        }

        // Another thread owns the computation; wait for its result.
        try {
            return existing.get(waitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            // Fail-open: compute independently rather than blocking indefinitely.
            return supplier.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    /** Returns the number of in-flight computations (useful for monitoring). */
    public int inflightCount() {
        return inflight.size();
    }
}
