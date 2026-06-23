package com.recsys.infrastructure.cache;
import com.recsys.infrastructure.EnvConfig;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory LRU cache for non-streaming LLM responses, keyed by SHA-256 of the request body.
 *
 * The same prompt always produces the same response from a deterministic model (temperature=0)
 * and often for low-temperature completions too. Caching avoids redundant upstream calls and
 * reduces token spend for repeated prompts (e.g. classification, structured extraction pipelines).
 *
 * Env vars:
 *   LLM_CACHE_MAX_SIZE    — max entries before LRU eviction (default 500, 0 = disabled)
 *   LLM_CACHE_TTL_SECONDS — entry TTL in seconds (default 300, 0 = disabled)
 */
public final class LlmResponseCache {

    public record Entry(int status, Map<String, List<String>> headers, byte[] body, long insertedAtMs) {}

    // MessageDigest is not thread-safe; one instance per thread avoids locking.
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    });

    private final Map<String, Entry> cache;
    private final long ttlMs;
    private final boolean enabled;

    LlmResponseCache(int maxSize, long ttlMs) {
        this.ttlMs = ttlMs;
        this.enabled = maxSize > 0 && ttlMs > 0;
        if (this.enabled) {
            int cap = maxSize;
            this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > cap;
                }
            });
        } else {
            this.cache = null;
        }
    }

    static LlmResponseCache disabled() {
        return new LlmResponseCache(0, 0);
    }

    public static LlmResponseCache fromEnvironment() {
        int maxSize = EnvConfig.readInt("LLM_CACHE_MAX_SIZE", 500);
        long ttlSeconds = EnvConfig.readLong("LLM_CACHE_TTL_SECONDS", 300L);
        return new LlmResponseCache(maxSize, ttlSeconds * 1000L);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Entry get(byte[] requestBody) {
        if (!enabled) return null;
        String key = hash(requestBody);
        Entry entry = cache.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.insertedAtMs() > ttlMs) {
            cache.remove(key);
            return null;
        }
        return entry;
    }

    public void put(byte[] requestBody, int status, Map<String, List<String>> headers, byte[] body) {
        if (!enabled) return;
        // Copy mutable body array; headers map from HttpResponse is already unmodifiable.
        cache.put(hash(requestBody), new Entry(status, headers, body.clone(), System.currentTimeMillis()));
    }

    int size() {
        if (!enabled) return 0;
        return cache.size();
    }

    private static String hash(byte[] data) {
        MessageDigest md = SHA256.get();
        md.reset();
        return HexFormat.of().formatHex(md.digest(data));
    }
}
