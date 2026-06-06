package com.recsys.infrastructure.vectordb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Sign-based random projection LSH (SimHash) for cosine similarity.
// Each embedding is hashed to a long bitmask by checking the sign of its dot product
// against numTables random Gaussian hyperplanes. Embeddings with similar cosine angles
// tend to land in the same bucket. Hamming-1 probing widens recall at modest cost.
public class EmbeddingLSH {

    private static final int DEFAULT_NUM_TABLES = 16;
    private static final long DEFAULT_SEED = 42L;

    private final float[][] hyperplanes; // [numTables][dim]
    private final ConcurrentHashMap<Long, List<Integer>> buckets;
    private final int numTables;

    public EmbeddingLSH(Map<Integer, float[]> embeddings) {
        this(embeddings, DEFAULT_NUM_TABLES, DEFAULT_SEED);
    }

    public EmbeddingLSH(Map<Integer, float[]> embeddings, int numTables, long seed) {
        if (embeddings.isEmpty()) throw new IllegalArgumentException("embeddings must not be empty");
        if (numTables < 1 || numTables > 63) throw new IllegalArgumentException("numTables must be 1–63");
        int dim = embeddings.values().iterator().next().length;
        this.numTables = numTables;
        this.hyperplanes = randomHyperplanes(numTables, dim, seed);
        this.buckets = buildBuckets(embeddings);
    }

    // Returns the candidate set: the exact bucket plus all Hamming-1 neighbors.
    // Probing 1-bit flips trades a modestly larger set for meaningful recall gains
    // when the dataset is small or the embedding dimension is high.
    public Set<Integer> candidates(float[] query) {
        long h = hash(query);
        Set<Integer> result = new HashSet<>(buckets.getOrDefault(h, List.of()));
        for (int i = 0; i < numTables; i++) {
            result.addAll(buckets.getOrDefault(h ^ (1L << i), List.of()));
        }
        return result;
    }

    // Add a new embedding to the index at runtime.
    // Safe to call concurrently with candidates() due to ConcurrentHashMap and CopyOnWriteArrayList.
    public void add(int id, float[] vec) {
        long h = hash(vec);
        buckets.computeIfAbsent(h, k -> new CopyOnWriteArrayList<>()).add(id);
    }

    long hash(float[] vec) {
        long h = 0;
        for (int i = 0; i < numTables; i++) {
            double dot = 0;
            float[] hp = hyperplanes[i];
            for (int j = 0; j < hp.length; j++) dot += hp[j] * vec[j];
            if (dot >= 0) h |= (1L << i);
        }
        return h;
    }

    private ConcurrentHashMap<Long, List<Integer>> buildBuckets(Map<Integer, float[]> embeddings) {
        ConcurrentHashMap<Long, List<Integer>> map = new ConcurrentHashMap<>();
        for (Map.Entry<Integer, float[]> e : embeddings.entrySet()) {
            map.computeIfAbsent(hash(e.getValue()), k -> new CopyOnWriteArrayList<>()).add(e.getKey());
        }
        return map;
    }

    private static float[][] randomHyperplanes(int numTables, int dim, long seed) {
        Random rng = new Random(seed);
        float[][] planes = new float[numTables][dim];
        for (float[] plane : planes) {
            for (int i = 0; i < dim; i++) plane[i] = (float) rng.nextGaussian();
        }
        return planes;
    }
}
