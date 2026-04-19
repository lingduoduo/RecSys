package com.recsys.features;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
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
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(vec[i]);
        }
        return sb.toString();
    }
}
