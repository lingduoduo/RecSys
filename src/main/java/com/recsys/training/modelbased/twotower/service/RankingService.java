package com.recsys.training.modelbased.twotower.service;

import com.recsys.features.VectorMath;
import com.recsys.training.modelbased.twotower.model.ScoredItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

@Service
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
        PriorityQueue<ScoredItem> best = new PriorityQueue<>(Comparator.comparingDouble(ScoredItem::score));

        for (ScoredItem recalled : recalledItems) {
            if (!seen.add(recalled.itemId())) continue;

            float[] itemEmbedding = itemEmbeddings.get(recalled.itemId());
            if (itemEmbedding == null) continue;

            double score = VectorMath.innerProduct(userEmbedding, itemEmbedding);
            if (score != Double.NEGATIVE_INFINITY) {
                ScoredItem rescored = new ScoredItem(recalled.itemId(), score);
                if (best.size() < k) {
                    best.offer(rescored);
                } else if (score > best.peek().score()) {
                    best.poll();
                    best.offer(rescored);
                }
            }
        }

        List<ScoredItem> result = new ArrayList<>(best);
        result.sort(Comparator.comparingDouble(ScoredItem::score).reversed());
        return result;
    }
}
