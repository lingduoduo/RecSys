package com.recsys.features;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class RedisEmbeddingStore implements EmbeddingStore {
    private static final Logger log = LoggerFactory.getLogger(RedisEmbeddingStore.class);
    private final JedisPool pool;
    private final String keyPrefix;
    private final int mgetBatchSize;
    // 缓存雪崩 — random TTL jitter: staggers expiry across keys so a batch write does not
    // cause all entries to expire simultaneously and trigger a thundering herd on Redis.
    // jitterFraction=0.1 means 0..10% of baseTtl is added as a random offset.
    private final double jitterFraction;

    public RedisEmbeddingStore(JedisPool pool, String keyPrefix) {
        this(pool, keyPrefix, 0.1);
    }

    RedisEmbeddingStore(JedisPool pool, String keyPrefix, double jitterFraction) {
        this(pool, keyPrefix, jitterFraction, readIntEnv("REDIS_EMBEDDING_MGET_BATCH_SIZE", 500));
    }

    RedisEmbeddingStore(JedisPool pool, String keyPrefix, double jitterFraction, int mgetBatchSize) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
        this.jitterFraction = Math.max(0.0, Math.min(0.5, jitterFraction));
        this.mgetBatchSize = Math.max(1, mgetBatchSize);
    }

    // Adds uniform positive jitter in [0, jitter] to baseTtl to spread expiry times.
    // Positive-only jitter avoids shortening the caller's intended minimum freshness window.
    long jitteredTtlMillis(long baseTtlSeconds) {
        if (baseTtlSeconds <= 0) return baseTtlSeconds;
        long baseMs = TimeUnit.SECONDS.toMillis(baseTtlSeconds);
        if (jitterFraction <= 0.0) return Math.max(1L, baseMs);
        long maxJitterMs = Math.max(1L, Math.round(baseMs * jitterFraction));
        long jitterMs = ThreadLocalRandom.current().nextLong(maxJitterMs + 1L);
        return Math.max(1L, baseMs + jitterMs);
    }

    @Override
    public float[] getEmbedding(int movieId) {
        try (Jedis jedis = pool.getResource()) {
            String v = jedis.get(keyPrefix + ":" + movieId);
            if (v == null || v.isBlank()) return null;
            return VectorMath.parseVector(v);
        }
    }

    public void setEmbeddingNoTtl(int movieId, float[] vector) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(keyPrefix + ":" + movieId, toVectorString(vector));
        }
    }

    // ttlSeconds <= 0 means no expiry
    @Override
    public void setEmbedding(int movieId, float[] vector, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            setEmbeddingNoTtl(movieId, vector);
            return;
        }
        SetParams params = SetParams.setParams().px(jitteredTtlMillis(ttlSeconds));
        try (Jedis jedis = pool.getResource()) {
            jedis.set(keyPrefix + ":" + movieId, toVectorString(vector), params);
        }
    }

    // Bulk write — mirrors the Spark/Scala pattern of iterating model vectors after training.
    // Uses a pipeline to send all SETs in one round-trip instead of N.
    // Each key gets an independently jittered millisecond TTL to stagger expiry (缓存雪崩/随机TTL):
    // when ttlSeconds > 0, every pipeline.set() call draws a fresh random jitter so entries
    // written in the same batch do not all expire at the same moment.
    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        if (vectors == null || vectors.isEmpty()) return;
        try (Jedis jedis = pool.getResource();
             Pipeline pipeline = jedis.pipelined()) {
            for (Map.Entry<Integer, float[]> entry : vectors.entrySet()) {
                String key = keyPrefix + ":" + entry.getKey();
                String value = toVectorString(entry.getValue());
                if (ttlSeconds > 0) {
                    pipeline.set(key, value, SetParams.setParams().px(jitteredTtlMillis(ttlSeconds)));
                } else {
                    pipeline.set(key, value);
                }
            }
            pipeline.sync();
        }
    }

    @Override
    public Map<Integer, float[]> getEmbeddings(Collection<Integer> movieIds) {
        Map<Integer, float[]> embeddings = new HashMap<>();
        if (movieIds == null || movieIds.isEmpty()) return embeddings;

        List<Integer> ids = new java.util.ArrayList<>(new LinkedHashSet<>(movieIds));
        if (ids.isEmpty()) {
            return embeddings;
        }

        try (Jedis jedis = pool.getResource()) {
            for (int start = 0; start < ids.size(); start += mgetBatchSize) {
                int end = Math.min(ids.size(), start + mgetBatchSize);
                String[] keys = new String[end - start];
                for (int i = start; i < end; i++) {
                    keys[i - start] = keyPrefix + ":" + ids.get(i);
                }
                List<String> values = jedis.mget(keys);
                for (int j = 0; j < values.size(); j++) {
                    String value = values.get(j);
                    if (value == null || value.isBlank()) continue;
                    embeddings.put(ids.get(start + j), VectorMath.parseVector(value));
                }
            }
        }

        return embeddings;
    }

    // SCAN all keys matching the prefix and MGET each page immediately.
    // Processing per scan page (≤500 keys at a time) caps peak heap use: the old approach
    // accumulated every key name into a List<String> then issued one unbounded MGET,
    // holding all keys + all serialised vectors simultaneously — an OOM / Full-GC risk
    // for large embedding stores.
    public Map<Integer, float[]> loadAll() {
        Map<Integer, float[]> result = new HashMap<>();
        ScanParams scanParams = new ScanParams().match(keyPrefix + ":*").count(500);

        try (Jedis jedis = pool.getResource()) {
            String cursor = "0";
            do {
                ScanResult<String> res = jedis.scan(cursor, scanParams);
                List<String> pageKeys = res.getResult();
                if (!pageKeys.isEmpty()) {
                    List<String> values = jedis.mget(pageKeys.toArray(new String[0]));
                    for (int i = 0; i < pageKeys.size(); i++) {
                        String val = values.get(i);
                        if (val == null || val.isBlank()) continue;
                        int sep = pageKeys.get(i).lastIndexOf(':');
                        if (sep < 0) continue;
                        try {
                            int id = Integer.parseInt(pageKeys.get(i).substring(sep + 1));
                            result.put(id, VectorMath.parseVector(val));
                        } catch (NumberFormatException ignore) {}
                    }
                }
                cursor = res.getCursor();
            } while (!"0".equals(cursor));
        }

        return result;
    }

    public Map<Integer, float[]> loadValidEmbeddings(Set<Integer> knownMovieIds) {
        Map<Integer, float[]> all = loadAll();
        int scannedCount = all.size();
        all.keySet().retainAll(knownMovieIds);
        log.info("Loaded {} valid embeddings (scanned {} keys in Redis, {} known movies)",
                all.size(), scannedCount, knownMovieIds.size());
        return all;
    }

    @Override
    public Set<Integer> scanIds(int maxKeys) {
        Set<Integer> ids = new HashSet<>();

        int countHint = Math.min(maxKeys, 500);
        ScanParams params = new ScanParams()
                .match(keyPrefix + ":*")
                .count(countHint);

        String cursor = "0";

        try (Jedis jedis = pool.getResource()) {
            while (true) {
                ScanResult<String> res = jedis.scan(cursor, params);

                for (String key : res.getResult()) {
                    int idx = key.lastIndexOf(':');
                    if (idx >= 0 && idx + 1 < key.length()) {
                        try {
                            ids.add(Integer.parseInt(key.substring(idx + 1)));
                        } catch (NumberFormatException ignore) {
                            // skip malformed keys
                        }
                    }
                    if (ids.size() >= maxKeys) return ids;
                }

                cursor = res.getCursor();
                if ("0".equals(cursor)) break;
            }
        }

        return ids;
    }

    private static String toVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 12);
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(vec[i]);
        }
        return sb.toString();
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
