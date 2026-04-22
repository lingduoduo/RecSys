package com.recsys.features;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

public class ExactVectorIndex implements VectorIndex {

    private final Map<Integer, float[]> embeddings;

    public ExactVectorIndex(Map<Integer, float[]> embeddings) {
        this.embeddings = Map.copyOf(embeddings);
    }

    @Override
    public List<SearchResult> search(float[] query, int k, Set<Integer> excludeIds) {
        return topK(query, k, excludeIds, embeddings.keySet());
    }

    protected List<SearchResult> topK(float[] query, int k, Set<Integer> excludeIds, Iterable<Integer> candidateIds) {
        if (query == null || k <= 0) return List.of();
        Set<Integer> excluded = Objects.requireNonNullElse(excludeIds, Set.of());

        PriorityQueue<SearchResult> best = new PriorityQueue<>(Comparator.comparingDouble(SearchResult::score));

        for (int id : candidateIds) {
            if (excluded.contains(id)) continue;
            float[] candidate = embeddings.get(id);
            if (candidate == null) continue;

            double score = VectorMath.innerProduct(query, candidate);
            if (score == Double.NEGATIVE_INFINITY) continue;

            if (best.size() < k) {
                best.offer(new SearchResult(id, score));
            } else if (score > best.peek().score()) {
                best.poll();
                best.offer(new SearchResult(id, score));
            }
        }

        List<SearchResult> results = new ArrayList<>(best);
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results;
    }

    @Override
    public String name() {
        return "exact";
    }
}
