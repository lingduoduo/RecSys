package com.recsys.training.modelbased.twotower.service;

import com.recsys.training.modelbased.twotower.model.RecommendRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FeatureEncoder {

    private final ModelArtifactService artifactService;
    private final int unkIndex;

    public FeatureEncoder(ModelArtifactService artifactService) {
        this.artifactService = artifactService;
        this.unkIndex = artifactService.getUserVocab().getOrDefault("__UNK__", 0);
    }

    public EncodedFeatures encode(RecommendRequest request) {
        Map<String, Integer> userVocab = artifactService.getUserVocab();
        long userId = userVocab.getOrDefault(request.getUserId(), unkIndex);
        return new EncodedFeatures(userId);
    }

    public static class EncodedFeatures {
        private final long userId;

        public EncodedFeatures(long userId) {
            this.userId = userId;
        }

        public long getUserId() {
            return userId;
        }
    }
}
