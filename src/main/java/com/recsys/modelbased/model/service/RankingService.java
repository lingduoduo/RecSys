package com.recsys.modelbased.model.service;

import com.recsys.features.VectorMath;
import com.recsys.modelbased.model.dto.ScoredItem;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public class RankingService {

    private final ModelArtifactService artifactService;

    public RankingService(ModelArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    public List<ScoredItem> rank(float[] userEmbedding, List<ScoredItem> recalledItems, int k) {
        if (userEmbedding == null || recalledItems == null || recalledItems.isEmpty() || k <= 0) {
            return List.of();
        }

        Map<String, float[]> itemEmbeddings = artifactService.getItemEmbeddings();
        Set<String> seen = new HashSet<>();
        PriorityQueue<ScoredItem> best = ScoredItems.minHeap();

        for (ScoredItem recalled : recalledItems) {
            if (!seen.add(recalled.itemId())) continue;

            float[] itemEmbedding = itemEmbeddings.get(recalled.itemId());
            if (itemEmbedding == null) continue;

            double score = VectorMath.innerProduct(userEmbedding, itemEmbedding);
            if (score != Double.NEGATIVE_INFINITY) {
                ScoredItems.keepTopK(best, new ScoredItem(recalled.itemId(), score), k);
            }
        }

        return ScoredItems.descending(best);
    }
}
