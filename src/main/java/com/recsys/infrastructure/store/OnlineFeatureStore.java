package com.recsys.infrastructure.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tier-2/Tier-3 bridge for online recommendation features.
 *
 * Online feature keys change on every user interaction (written by Flink) so they
 * must live in Redis (Tier 2). However, recommendation requests read the same key
 * many times per second for active users or hot movies. A short-TTL JVM-heap cache (Tier 3) absorbs
 * those repeated reads without sacrificing meaningful freshness — a 5-second stale
 * window is imperceptible to recommendation quality.
 */
public final class OnlineFeatureStore implements RecentHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(OnlineFeatureStore.class);

    private static final long DEFAULT_CACHE_TTL_MS = 5_000L;
    private static final long DEFAULT_STALE_TTL_MS = 60_000L;
    private static final int DEFAULT_MAX_CACHE_USERS = 10_000;
    private static final long EVICT_INTERVAL_MS = 5_000L;
    private static final long REDIS_FETCH_TIMEOUT_MS = 2_000L;

    private final Pool<Jedis> pool;
    private final long cacheTtlMs;
    private final long staleTtlMs;
    private final int maxCacheUsers;
    private final int redisMgetBatchSize;
    private final ConcurrentHashMap<String, CachedFeature> featureCache = new ConcurrentHashMap<>();
    // Deduplicates concurrent cache misses for the same Redis feature key.
    private final ConcurrentHashMap<String, CompletableFuture<CachedFeature>> inflight = new ConcurrentHashMap<>();
    private volatile long lastEvictMs = 0L;

    public OnlineFeatureStore(Pool<Jedis> pool) {
        this(pool, DEFAULT_CACHE_TTL_MS,
                readLongEnv("ONLINE_FEATURE_STALE_TTL_MS", DEFAULT_STALE_TTL_MS),
                readIntEnv("ONLINE_FEATURE_CACHE_MAX_USERS", DEFAULT_MAX_CACHE_USERS));
    }

    OnlineFeatureStore(Pool<Jedis> pool, long cacheTtlMs) {
        this(pool, cacheTtlMs, DEFAULT_STALE_TTL_MS, DEFAULT_MAX_CACHE_USERS);
    }

    OnlineFeatureStore(Pool<Jedis> pool, long cacheTtlMs, int maxCacheUsers) {
        this(pool, cacheTtlMs, DEFAULT_STALE_TTL_MS, maxCacheUsers);
    }

    OnlineFeatureStore(Pool<Jedis> pool, long cacheTtlMs, long staleTtlMs, int maxCacheUsers) {
        this(pool, cacheTtlMs, staleTtlMs, maxCacheUsers,
                readIntEnv("ONLINE_FEATURE_REDIS_MGET_BATCH_SIZE", 500));
    }

    OnlineFeatureStore(Pool<Jedis> pool, long cacheTtlMs, int maxCacheUsers, int redisMgetBatchSize) {
        this(pool, cacheTtlMs, DEFAULT_STALE_TTL_MS, maxCacheUsers, redisMgetBatchSize);
    }

    OnlineFeatureStore(Pool<Jedis> pool, long cacheTtlMs, long staleTtlMs,
                       int maxCacheUsers, int redisMgetBatchSize) {
        this.pool = pool;
        this.cacheTtlMs = cacheTtlMs;
        this.staleTtlMs = Math.max(cacheTtlMs, staleTtlMs);
        this.maxCacheUsers = Math.max(1, maxCacheUsers);
        this.redisMgetBatchSize = Math.max(1, redisMgetBatchSize);
    }

    @Override
    public List<Integer> getRecentMovieIds(int userId, int limit) {
        return applyLimit(parseMovieIds(getFeature("user:" + userId + ":recent_movies")), limit);
    }

    public String getFeature(String redisKey) {
        CachedFeature feature = getCachedOrLoad(redisKey, System.currentTimeMillis());
        return feature == null ? null : feature.value;
    }

    public Map<String, String> getFeatures(Collection<String> redisKeys) {
        if (redisKeys == null || redisKeys.isEmpty()) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> misses = new LinkedHashSet<>();

        for (String redisKey : redisKeys) {
            if (redisKey == null || redisKey.isBlank()) {
                continue;
            }
            String key = redisKey.trim();
            CachedFeature cached = featureCache.get(key);
            if (cached != null && cached.expiresAtMs > now) {
                if (cached.value != null) {
                    result.put(key, cached.value);
                }
            } else {
                misses.add(key);
            }
        }

        if (misses.isEmpty()) {
            return result;
        }

        // Snapshot stale values before eviction so they cannot be removed before we read them.
        Map<String, String> staleByKey = new LinkedHashMap<>();
        for (String key : misses) {
            CachedFeature cached = featureCache.get(key);
            if (cached != null && cached.staleExpiresAtMs() > now && cached.value() != null) {
                staleByKey.put(key, cached.value());
            }
        }

        evictIfNeeded(now);

        try {
            Map<String, CachedFeature> fetched = fetchFeaturesFromRedis(misses, now);
            fetched.forEach((key, feature) -> {
                featureCache.put(key, feature);
                if (feature.value() != null) {
                    result.put(key, feature.value());
                }
            });
        } catch (RuntimeException e) {
            log.warn("getFeatures Redis batch failed; serving {} stale values: {}",
                    staleByKey.size(), e.toString());
            log.debug("getFeatures Redis batch failure details", e);
            result.putAll(staleByKey);
        }
        return result;
    }

    private CachedFeature getCachedOrLoad(String redisKey, long now) {
        if (redisKey == null || redisKey.isBlank()) {
            return null;
        }
        String key = redisKey.trim();
        CachedFeature cached = featureCache.get(key);
        if (cached != null && cached.expiresAtMs > now) {
            return cached;
        }

        CompletableFuture<CachedFeature> myFuture = new CompletableFuture<>();
        CompletableFuture<CachedFeature> existing = inflight.putIfAbsent(key, myFuture);
        if (existing == null) {
            try {
                evictIfNeeded(now);
                CachedFeature fresh = fetchFeatureFromRedis(key, now);
                featureCache.put(key, fresh);
                myFuture.complete(fresh);
                return fresh;
            } catch (RuntimeException ex) {
                if (cached != null && cached.staleExpiresAtMs > now) {
                    myFuture.complete(cached);
                    return cached;
                }
                myFuture.completeExceptionally(ex);
                throw ex;
            } finally {
                inflight.remove(key, myFuture);
            }
        }

        try {
            return existing.get(REDIS_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException | ExecutionException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return fetchFeatureFromRedis(key, now);
        }
    }

    private CachedFeature fetchFeatureFromRedis(String redisKey, long now) {
        try (Jedis jedis = pool.getResource()) {
            return cachedFeature(jedis.get(redisKey), now);
        }
    }

    private Map<String, CachedFeature> fetchFeaturesFromRedis(Collection<String> redisKeys, long now) {
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(redisKeys));
        Map<String, CachedFeature> result = new HashMap<>(keys.size() * 2);
        try (Jedis jedis = pool.getResource()) {
            for (int start = 0; start < keys.size(); start += redisMgetBatchSize) {
                int end = Math.min(keys.size(), start + redisMgetBatchSize);
                String[] batch = keys.subList(start, end).toArray(new String[0]);
                List<String> values = jedis.mget(batch);
                for (int i = 0; i < values.size(); i++) {
                    result.put(batch[i], cachedFeature(values.get(i), now));
                }
            }
        }
        return result;
    }

    private static List<Integer> parseMovieIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        String[] tokens = value.trim().split("\\s+");
        List<Integer> ids = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            try {
                ids.add(Integer.parseInt(token));
            } catch (NumberFormatException ignore) {
                // Ignore malformed feature values so one bad token does not break the demo.
            }
        }
        return List.copyOf(ids);
    }

    private static List<Integer> applyLimit(List<Integer> ids, int limit) {
        if (ids.size() <= limit) return ids;
        return ids.subList(ids.size() - limit, ids.size());
    }

    private void evictIfNeeded(long now) {
        if (featureCache.size() < maxCacheUsers) return;
        // Rate-limit eviction scans: a full O(N) removeIf on every miss is too expensive.
        if (now - lastEvictMs < EVICT_INTERVAL_MS) return;
        lastEvictMs = now;
        // First pass: remove genuinely expired entries.
        featureCache.entrySet().removeIf(entry -> entry.getValue().staleExpiresAtMs <= now);
        // Second pass: if still over capacity, evict arbitrary live entries via an
        // iterator — safe under concurrent modification, avoids Enumeration.nextElement()
        // which can throw NoSuchElementException when the map is modified concurrently.
        if (featureCache.size() >= maxCacheUsers) {
            Iterator<String> it = featureCache.keySet().iterator();
            while (featureCache.size() >= maxCacheUsers && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    public int cacheSize() {
        return featureCache.size();
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

    private static long readLongEnv(String envName, long defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private CachedFeature cachedFeature(String value, long now) {
        return new CachedFeature(value, now + cacheTtlMs, now + staleTtlMs);
    }

    private record CachedFeature(String value, long expiresAtMs, long staleExpiresAtMs) {}
}
