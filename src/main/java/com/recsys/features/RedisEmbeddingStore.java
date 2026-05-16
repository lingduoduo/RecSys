package com.recsys.features;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RedisEmbeddingStore implements EmbeddingStore {
    private static final Logger log = LoggerFactory.getLogger(RedisEmbeddingStore.class);
    private final JedisPool pool;
    private final String keyPrefix;

    public RedisEmbeddingStore(JedisPool pool, String keyPrefix) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
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
        SetParams params = SetParams.setParams().ex(ttlSeconds);
        try (Jedis jedis = pool.getResource()) {
            jedis.set(keyPrefix + ":" + movieId, toVectorString(vector), params);
        }
    }

    // Bulk write — mirrors the Spark/Scala pattern of iterating model vectors after training.
    // Uses a pipeline to send all SETs in one round-trip instead of N.
    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        if (vectors == null || vectors.isEmpty()) return;
        SetParams params = ttlSeconds > 0 ? SetParams.setParams().ex(ttlSeconds) : null;
        try (Jedis jedis = pool.getResource();
             Pipeline pipeline = jedis.pipelined()) {
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

    @Override
    public Map<Integer, float[]> getEmbeddings(Collection<Integer> movieIds) {
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

    // SCAN all keys matching the prefix then MGET all values in one connection.
    // Avoids the two-connection overhead of scanIds() + getEmbeddings() and removes
    // any arbitrary key cap.
    public Map<Integer, float[]> loadAll() {
        List<String> keys = new ArrayList<>();
        ScanParams scanParams = new ScanParams().match(keyPrefix + ":*").count(500);

        try (Jedis jedis = pool.getResource()) {
            String cursor = "0";
            do {
                ScanResult<String> res = jedis.scan(cursor, scanParams);
                keys.addAll(res.getResult());
                cursor = res.getCursor();
            } while (!"0".equals(cursor));

            if (keys.isEmpty()) return new HashMap<>();

            List<String> values = jedis.mget(keys.toArray(new String[0]));
            Map<Integer, float[]> result = new HashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                String val = values.get(i);
                if (val == null || val.isBlank()) continue;
                int sep = keys.get(i).lastIndexOf(':');
                if (sep < 0) continue;
                try {
                    int id = Integer.parseInt(keys.get(i).substring(sep + 1));
                    result.put(id, VectorMath.parseVector(val));
                } catch (NumberFormatException ignore) {}
            }
            return result;
        }
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
}
