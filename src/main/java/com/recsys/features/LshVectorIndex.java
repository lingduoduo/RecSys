package com.recsys.features;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

public class LshVectorIndex extends ExactVectorIndex {

    private final EmbeddingLSH lsh;
    private final Set<Integer> allIds;

    public LshVectorIndex(Map<Integer, float[]> embeddings) {
        super(embeddings);
        this.lsh = new EmbeddingLSH(embeddings);
        this.allIds = Set.copyOf(embeddings.keySet());
    }

    @Override
    public List<SearchResult> search(float[] query, int k, Set<Integer> excludeIds) {
        Set<Integer> candidates = new HashSet<>(lsh.candidates(query));
        candidates.removeAll(excludeIds);

        if (candidates.size() < k) {
            candidates = allIds;
        }

        return topK(query, k, excludeIds, candidates);
    }

    @Override
    public String name() {
        return "lsh";
    }
}
