package com.recsys.features;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RedisEmbeddingStore {
    private final JedisPool pool;
    private final String keyPrefix;

    public RedisEmbeddingStore(JedisPool pool, String keyPrefix) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
    }

    public float[] getMovieEmbedding(int movieId) {
        try (Jedis jedis = pool.getResource()) {
            String v = jedis.get(keyPrefix + ":" + movieId);
            if (v == null || v.isBlank()) return null;
            return VectorMath.parseVector(v);
        }
    }

    public void setMovieEmbedding(int movieId, float[] vector) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(keyPrefix + ":" + movieId, toVectorString(vector));
        }
    }

    // ttlSeconds <= 0 means no expiry
    public void setMovieEmbedding(int movieId, float[] vector, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            setMovieEmbedding(movieId, vector);
            return;
        }
        SetParams params = SetParams.setParams().ex(ttlSeconds);
        try (Jedis jedis = pool.getResource()) {
            jedis.set(keyPrefix + ":" + movieId, toVectorString(vector), params);
        }
    }

    // Bulk write — mirrors the Spark/Scala pattern of iterating model vectors after training.
    // Uses a pipeline to send all SETs in one round-trip instead of N.
    public void setMovieEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        if (vectors == null || vectors.isEmpty()) return;
        SetParams params = ttlSeconds > 0 ? SetParams.setParams().ex(ttlSeconds) : null;
        try (Jedis jedis = pool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Map.Entry<Integer, float[]> entry : vectors.entrySet()) {
                String key = keyPrefix + ":" + entry.getKey();
                String value = toVectorString(entry.getValue());
                if (params != null) {
                    pipeline.set(key, value, params);
                } else {
                    pipeline.set(key, value);
                }
            }
            pipeline.sync();
        }
    }

    public Map<Integer, float[]> getMovieEmbeddings(Collection<Integer> movieIds) {
        Map<Integer, float[]> embeddings = new HashMap<>();
        if (movieIds == null || movieIds.isEmpty()) return embeddings;

        int i = 0;
        int[] idArray = new int[movieIds.size()];
        String[] keys = new String[movieIds.size()];
        for (int id : movieIds) {
            idArray[i] = id;
            keys[i] = keyPrefix + ":" + id;
            i++;
        }

        try (Jedis jedis = pool.getResource()) {
            List<String> values = jedis.mget(keys);
            for (int j = 0; j < values.size(); j++) {
                String value = values.get(j);
                if (value == null || value.isBlank()) continue;
                embeddings.put(idArray[j], VectorMath.parseVector(value));
            }
        }

        return embeddings;
    }

    /**
     * Mirrors the Java reference pattern: scan all embedding keys in Redis, validate each
     * movieId against knownMovieIds (skip unknowns), then batch-fetch and return valid embeddings.
     *
     * Uses SCAN + MGET instead of KEYS + N×GET for production safety and efficiency.
     * Returns a map of movieId -> embedding and logs the valid count.
     */
    public Map<Integer, float[]> loadValidEmbeddings(Set<Integer> knownMovieIds) {
        Set<Integer> redisIds = scanMovieIds(Integer.MAX_VALUE);
        redisIds.retainAll(knownMovieIds);  // skip IDs not in the movie store
        Map<Integer, float[]> embeddings = getMovieEmbeddings(redisIds);
        System.out.printf("[RedisEmbeddingStore] loaded %d valid embeddings (scanned %d keys in Redis, %d known movies)%n",
                embeddings.size(), redisIds.size(), knownMovieIds.size());
        return embeddings;
    }

    public Set<Integer> scanMovieIds(int maxKeys) {
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
}
