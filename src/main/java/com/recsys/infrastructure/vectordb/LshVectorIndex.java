package com.recsys.infrastructure.vectordb;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class LshVectorIndex extends ExactVectorIndex {

    private final EmbeddingLSH lsh;
    private final Set<Integer> allIds;

    public LshVectorIndex(Map<Integer, float[]> embeddings) {
        super(embeddings);
        this.lsh = new EmbeddingLSH(embeddings);
        this.allIds = ConcurrentHashMap.newKeySet();
        this.allIds.addAll(embeddings.keySet());
    }

    @Override
    public void addOrUpdate(int id, float[] vec) {
        super.addOrUpdate(id, vec);  // update ConcurrentHashMap in ExactVectorIndex
        lsh.add(id, vec);            // update LSH buckets
        allIds.add(id);              // expose id to full-scan fallback
    }

    @Override
    public List<SearchResult> search(float[] query, int k, Set<Integer> excludeIds) {
        Set<Integer> excluded = Objects.requireNonNullElse(excludeIds, Set.of());
        Set<Integer> lshCandidates = lsh.candidates(query);

        // Skip the HashSet copy + removeAll if raw LSH output is already too small to fill k
        // results even before exclusion — we'll need a full scan regardless.
        if (lshCandidates.size() < k) {
            return topK(query, k, excluded, allIds);
        }

        Set<Integer> candidates = new HashSet<>(lshCandidates);
        candidates.removeAll(excluded);

        if (candidates.size() < k) {
            return topK(query, k, excluded, allIds);
        }

        return topK(query, k, excluded, candidates);
    }

    @Override
    public String name() {
        return "lsh";
    }
}
