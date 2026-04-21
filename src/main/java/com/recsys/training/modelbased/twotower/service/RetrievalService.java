package com.recsys.training.modelbased.twotower.service;

import com.recsys.features.VectorMath;
import com.recsys.training.modelbased.twotower.model.ScoredItem;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RetrievalService {

    private final ModelArtifactService artifactService;

    public RetrievalService(ModelArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    public List<ScoredItem> retrieve(float[] userEmbedding, int k, Set<String> excludeItems) {
        double userNormSq = VectorMath.normSq(userEmbedding);
        List<ScoredItem> scored = new ArrayList<>();

        for (Map.Entry<String, float[]> entry : artifactService.getItemEmbeddings().entrySet()) {
            String itemId = entry.getKey();
            if (excludeItems.contains(itemId)) {
                continue;
            }
            double score = VectorMath.cosine(userEmbedding, userNormSq, entry.getValue());
            scored.add(new ScoredItem(itemId, score));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredItem::getScore).reversed())
                .limit(k)
                .collect(Collectors.toList());
    }
}
