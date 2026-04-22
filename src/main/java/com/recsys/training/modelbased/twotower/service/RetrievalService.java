package com.recsys.training.modelbased.twotower.service;

import com.recsys.features.VectorMath;
import com.recsys.training.modelbased.twotower.model.ScoredItem;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RetrievalService {

    private final ModelArtifactService artifactService;

    public RetrievalService(ModelArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    public List<ScoredItem> retrieve(float[] userEmbedding, int k, Set<String> excludeItems) {
        double userNormSq = VectorMath.normSq(userEmbedding);
        // Min-heap of size k: O(n log k) instead of O(n log n) sort-then-limit
        PriorityQueue<ScoredItem> best = new PriorityQueue<>(Comparator.comparingDouble(ScoredItem::score));

        for (Map.Entry<String, float[]> entry : artifactService.getItemEmbeddings().entrySet()) {
            String itemId = entry.getKey();
            if (excludeItems.contains(itemId)) continue;
            double score = VectorMath.cosine(userEmbedding, userNormSq, entry.getValue());
            if (score == Double.NEGATIVE_INFINITY) continue;
            if (best.size() < k) {
                best.offer(new ScoredItem(itemId, score));
            } else if (score > best.peek().score()) {
                best.poll();
                best.offer(new ScoredItem(itemId, score));
            }
        }

        List<ScoredItem> result = new ArrayList<>(best);
        result.sort(Comparator.comparingDouble(ScoredItem::score).reversed());
        return result;
    }
}
