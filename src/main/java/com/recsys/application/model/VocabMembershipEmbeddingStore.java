package com.recsys.application.model;

import com.recsys.infrastructure.vectordb.EmbeddingStore;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Adapts the ONNX model's user vocab into an {@link EmbeddingStore} for warm/cold classification.
 * {@code MultiChannelRecallService} treats a user as cold when {@code getEmbedding(parseInt(userId))}
 * is null; this makes cold ⇔ "not in the model user vocab" (the port-8080 source of truth), without
 * changing the shared recall service. The sentinel vector is never used for ANN — the embedding
 * channel uses a separate {@code CandidateGenerator} store.
 */
public class VocabMembershipEmbeddingStore implements EmbeddingStore {

    // Sentinel: callers MUST only null-check the returned array, never read or mutate it
    // (MultiChannelRecallService uses it solely for warm/cold presence detection).
    private static final float[] PRESENT = new float[]{1.0f};

    private final Set<String> members;

    public VocabMembershipEmbeddingStore(Map<String, Integer> userVocab) {
        Set<String> copy = new HashSet<>(userVocab.keySet());
        copy.remove("__UNK__");
        this.members = Set.copyOf(copy);
    }

    @Override
    public float[] getEmbedding(int id) {
        return members.contains(Integer.toString(id)) ? PRESENT : null;
    }

    @Override
    public float[] getEmbeddingPrimary(int id) {
        return getEmbedding(id);
    }

    @Override
    public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
        return Map.of();
    }

    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        // inert: this adapter is read-only membership, recall never writes through it
    }

    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        // inert
    }

    @Override
    public Set<Integer> scanIds(int maxKeys) {
        return Set.of();
    }
}
