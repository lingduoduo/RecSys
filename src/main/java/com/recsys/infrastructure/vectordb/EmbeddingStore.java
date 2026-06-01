package com.recsys.infrastructure.vectordb;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Read/write contract for item embedding storage.
 *
 * Implementations follow a three-tier hierarchy:
 *   1. File system  — full offline embeddings persisted as classpath resources (source of truth)
 *   2. Redis        — online embeddings written by Flink or /setembedding; read at serving time
 *   3. JVM heap     — LocalEmbeddingCache sits in front of Redis so hot embeddings never pay
 *                     a network round-trip on the critical serving path
 */
public interface EmbeddingStore {

    /** Returns the embedding for {@code id}, or {@code null} if not found. */
    float[] getEmbedding(int id);

    /** Bulk get; absent IDs are silently omitted from the returned map. */
    Map<Integer, float[]> getEmbeddings(Collection<Integer> ids);

    /** Write a single embedding. {@code ttlSeconds <= 0} means no expiry. */
    void setEmbedding(int id, float[] vector, long ttlSeconds);

    /** Bulk write. {@code ttlSeconds <= 0} means no expiry. */
    void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds);

    /** Returns up to {@code maxKeys} known IDs (used for seeding checks). */
    Set<Integer> scanIds(int maxKeys);
}
