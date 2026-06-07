package com.recsys.featureflags.providers;

import com.recsys.featureflags.FeatureFlagProvider;
import com.recsys.featureflags.models.FeatureFlag;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public class CachingFeatureFlagProvider implements FeatureFlagProvider {

    private final FeatureFlagProvider delegate;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public CachingFeatureFlagProvider(FeatureFlagProvider delegate, Duration ttl) {
        this(delegate, ttl, System::nanoTime);
    }

    CachingFeatureFlagProvider(FeatureFlagProvider delegate, Duration ttl, LongSupplier clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ttlNanos = Objects.requireNonNull(ttl, "ttl").toNanos();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<Boolean> resolve(FeatureFlag flag, String distinctId, Map<String, Object> properties) {
        CacheKey key = new CacheKey(flag.key(), distinctId);
        long nowNanos = clock.getAsLong();
        CacheEntry existing = cache.get(key);
        if (existing != null && nowNanos < existing.expiryNanos()) {
            return Optional.ofNullable(existing.value());
        }
        Optional<Boolean> result = delegate.resolve(flag, distinctId, properties);
        cache.put(key, new CacheEntry(result.orElse(null), nowNanos + ttlNanos));
        return result;
    }

    /** Removes all expired entries. Call periodically to bound memory when user cardinality is high. */
    public void evictExpired() {
        long nowNanos = clock.getAsLong();
        cache.entrySet().removeIf(e -> nowNanos >= e.getValue().expiryNanos());
    }

    private record CacheKey(String flagKey, String distinctId) {}
    private record CacheEntry(Boolean value, long expiryNanos) {}
}
