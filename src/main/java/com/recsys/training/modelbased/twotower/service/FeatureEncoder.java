package com.recsys.training.modelbased.twotower.service;

import com.recsys.training.modelbased.twotower.model.RecommendRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FeatureEncoder {

    private final ModelArtifactService artifactService;

    public FeatureEncoder(ModelArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    public EncodedFeatures encode(RecommendRequest request) {
        Map<String, Integer> userVocab = artifactService.getUserVocab();
        long userId = lookup(userVocab, request.getUserId());
        return new EncodedFeatures(userId);
    }

    private long lookup(Map<String, Integer> vocab, String value) {
        if (value == null) {
            return vocab.getOrDefault("__UNK__", 0);
        }
        return vocab.getOrDefault(value, vocab.getOrDefault("__UNK__", 0));
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
