package com.recsys.infrastructure.cache;

import com.recsys.infrastructure.SingleFlight;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Single-value-per-key snapshot cache with a short fresh TTL, single-flight refresh,
 * and serve-stale-on-error. Generalises the cache lifecycle already used inline by
 * {@code ShardedTopKStore} so other stores (e.g. {@code GlobalPopularityStore}) can
 * reuse one tested implementation.
 *
 * Read semantics ({@link #get}):
 *   1. Fresh hit (now &lt; freshUntil): return cached value, loader not called.
 *   2. Stale window (freshUntil &le; now &lt; staleUntil): one caller refreshes
 *      (non-blocking guard); concurrent callers are served the last value. If the
 *      refresh loader throws, the stale value is kept and served.
 *   3. Cold miss / beyond stale: block-and-load, coalescing concurrent callers via
 *      {@link SingleFlight}; a loader exception propagates.
 */
public final class TtlSingleFlightCache<V> {

    public static final long DEFAULT_FRESH_TTL_MS = 1_000L;
    public static final long DEFAULT_STALE_TTL_MS = 60_000L;
    private static final long SINGLE_FLIGHT_WAIT_MS = 2_000L;

    private static final class Entry<V> {
        final V value;
        final long freshUntil;
        final long staleUntil;
        Entry(V value, long freshUntil, long staleUntil) {
            this.value = value;
            this.freshUntil = freshUntil;
            this.staleUntil = staleUntil;
        }
    }

    private final long freshTtlMs;
    private final long staleTtlMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private final SingleFlight<String, V> singleFlight = new SingleFlight<>(SINGLE_FLIGHT_WAIT_MS);

    public TtlSingleFlightCache(long freshTtlMs, long staleTtlMs) {
        this(freshTtlMs, staleTtlMs, System::currentTimeMillis);
    }

    public TtlSingleFlightCache(long freshTtlMs, long staleTtlMs, LongSupplier clock) {
        this.freshTtlMs = Math.max(1L, freshTtlMs);
        this.staleTtlMs = Math.max(this.freshTtlMs, staleTtlMs);
        this.clock = clock;
    }

    public V get(String key, Supplier<V> loader) {
        long now = clock.getAsLong();
        Entry<V> e = entries.get(key);

        if (e != null && now < e.freshUntil) {
            return e.value;                              // 1. fresh hit
        }

        if (e != null && now < e.staleUntil) {           // 2. stale window
            if (refreshing.add(key)) {
                try {
                    store(key, loader.get(), clock.getAsLong());
                } catch (RuntimeException keepStale) {
                    // swallow: keep serving the stale value until staleUntil
                } finally {
                    refreshing.remove(key);
                }
                Entry<V> updated = entries.get(key);
                return updated != null ? updated.value : e.value;
            }
            return e.value;                              // another thread refreshing → serve stale
        }

        // 3. cold miss or beyond stale: block-and-load, coalescing concurrent callers
        return singleFlight.execute(key, () -> {
            long t = clock.getAsLong();
            Entry<V> cur = entries.get(key);
            if (cur != null && t < cur.freshUntil) return cur.value;
            V fresh = loader.get();                      // may throw → propagates
            store(key, fresh, t);
            return fresh;
        });
    }

    private void store(String key, V value, long now) {
        entries.put(key, new Entry<>(value, now + freshTtlMs, now + staleTtlMs));
    }
}
