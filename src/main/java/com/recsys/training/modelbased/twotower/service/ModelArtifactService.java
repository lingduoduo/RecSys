package com.recsys.training.modelbased.twotower.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

@Service
public class ModelArtifactService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, float[]> itemEmbeddingCache;

    private String modelVersion;
    private Map<String, Integer> userVocab = new HashMap<>();

    public ModelArtifactService(ConcurrentMap<String, float[]> itemEmbeddingCache) {
        this.itemEmbeddingCache = itemEmbeddingCache;
    }

    @PostConstruct
    public void loadArtifacts() throws IOException {
        loadFeatureConfig();
        loadItemEmbeddings();
    }

    private void loadFeatureConfig() throws IOException {
        ClassPathResource resource = new ClassPathResource("model/feature_config.json");
        if (!resource.exists()) {
            throw new IllegalStateException("model/feature_config.json not found. Run python-training/train_and_export.py first.");
        }

        try (InputStream is = resource.getInputStream()) {
            Map<String, Object> config = objectMapper.readValue(is, new TypeReference<>() {});
            this.modelVersion = String.valueOf(config.getOrDefault("model_version", "unknown"));
            this.userVocab = convertToIntMap(config.get("user_vocab"));
        }
    }

    private void loadItemEmbeddings() throws IOException {
        ClassPathResource resource = new ClassPathResource("model/item_embeddings.json");
        if (!resource.exists()) {
            throw new IllegalStateException("model/item_embeddings.json not found. Run python-training/train_and_export.py first.");
        }

        try (InputStream is = resource.getInputStream()) {
            Map<String, List<Double>> raw = objectMapper.readValue(is, new TypeReference<>() {});
            for (Map.Entry<String, List<Double>> entry : raw.entrySet()) {
                List<Double> values = entry.getValue();
                float[] vec = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    vec[i] = values.get(i).floatValue();
                }
                itemEmbeddingCache.put(entry.getKey(), vec);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> convertToIntMap(Object input) {
        Map<String, Object> raw = (Map<String, Object>) input;
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            out.put(e.getKey(), ((Number) e.getValue()).intValue());
        }
        return out;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public Map<String, Integer> getUserVocab() {
        return userVocab;
    }

    public Map<String, float[]> getItemEmbeddings() {
        return itemEmbeddingCache;
    }
}
