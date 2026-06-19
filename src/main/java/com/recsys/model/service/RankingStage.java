package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.model.dto.ScoredItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Hybrid two-tier ranking. ONNX-scores the candidates that are in the model item vocab (tier 1,
 * sorted by model score); candidates outside the vocab keep their recall score (tier 2, appended
 * below). Strict tiering — the model's known items always rank above fresh/unknown ones — so the
 * two score scales never need reconciling. Replaces the legacy inner-product RankingService.
 */
public class RankingStage {

    private final UserTowerInferenceService inference;
    private final FeatureEncoder featureEncoder;
    private final ModelArtifactService artifactService;

    public RankingStage(UserTowerInferenceService inference,
                        FeatureEncoder featureEncoder,
                        ModelArtifactService artifactService) {
        this.inference = inference;
        this.featureEncoder = featureEncoder;
        this.artifactService = artifactService;
    }

    public List<ScoredItem> rank(FeatureEncoder.EncodedFeatures user, List<MovieCandidate> candidates, int k) {
        if (candidates == null || candidates.isEmpty() || k <= 0) {
            return List.of();
        }
        Map<String, Integer> itemVocab = artifactService.getItemVocab();

        LinkedHashSet<String> inVocab = new LinkedHashSet<>();
        List<MovieCandidate> outOfVocab = new ArrayList<>();
        for (MovieCandidate c : candidates) {
            if (itemVocab.containsKey(c.itemId())) {
                inVocab.add(c.itemId());
            } else {
                outOfVocab.add(c);
            }
        }

        // Tier 1: ONNX score the in-vocab candidates (already returned sorted desc, capped at k).
        List<ScoredItem> tier1 = inference.scoreCandidates(user, featureEncoder, inVocab, k);

        // Tier 2: out-of-vocab kept at their recall score, highest first.
        List<ScoredItem> tier2 = outOfVocab.stream()
                .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed())
                .map(c -> new ScoredItem(c.itemId(), c.score()))
                .toList();

        Map<String, ScoredItem> merged = new LinkedHashMap<>();
        for (ScoredItem s : tier1) merged.putIfAbsent(s.itemId(), s);
        for (ScoredItem s : tier2) merged.putIfAbsent(s.itemId(), s);
        return merged.values().stream().limit(k).toList();
    }
}
