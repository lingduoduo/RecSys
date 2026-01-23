package com.example;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RedisEmbeddingStore {
    private final String host;
    private final int port;
    private final String keyPrefix;

    public RedisEmbeddingStore(String host, int port, String keyPrefix) {
        this.host = host;
        this.port = port;
        this.keyPrefix = keyPrefix;
    }

    public float[] getMovieEmbedding(int movieId) {
        try (Jedis jedis = new Jedis(host, port)) {
            String v = jedis.get(keyPrefix + ":" + movieId);
            if (v == null || v.isBlank()) return null;
            return parseVector(v);
        }
    }

    public void setMovieEmbedding(int movieId, float[] vector) {
        try (Jedis jedis = new Jedis(host, port)) {
            jedis.set(keyPrefix + ":" + movieId, toVectorString(vector));
        }
    }

    public List<Integer> getCandidateMovieIds() {
        return new ArrayList<>(DataManager.getInstance().getAllMovieIds());
    }

    public Set<Integer> scanMovieIds(int maxKeys) {
        Set<Integer> ids = new HashSet<>();

        ScanParams params = new ScanParams()
                .match(keyPrefix + ":*")
                .count(200);

        String cursor = "0";

        try (Jedis jedis = new Jedis(host, port)) {
            while (true) {
                ScanResult<String> res = jedis.scan(cursor, params);

                for (String key : res.getResult()) {
                    // key format: i2vEmb:<id>
                    int idx = key.lastIndexOf(':');
                    if (idx >= 0 && idx + 1 < key.length()) {
                        try {
                            ids.add(Integer.parseInt(key.substring(idx + 1)));
                        } catch (NumberFormatException ignore) {
                            // skip
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

    private static float[] parseVector(String s) {
        String[] parts = s.trim().split("\\s+");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) vec[i] = Float.parseFloat(parts[i]);
        return vec;
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
