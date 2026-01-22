package com.example;

import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;

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

    // OPTIONAL for prototyping: write embedding (so you can seed data quickly)
    public void setMovieEmbedding(int movieId, float[] vector) {
        try (Jedis jedis = new Jedis(host, port)) {
            jedis.set(keyPrefix + ":" + movieId, toVectorString(vector));
        }
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

    // Used for brute-force similarity prototype: list candidate IDs somehow
    // For a quick start, we’ll keep IDs in DataManager (movie catalog).
    public List<Integer> getCandidateMovieIds() {
        return new ArrayList<>(DataManager.getInstance().getAllMovieIds());
    }
}
