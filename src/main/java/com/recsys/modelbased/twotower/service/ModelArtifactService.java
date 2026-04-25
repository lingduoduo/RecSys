package com.recsys.modelbased.twotower.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Service
public class ModelArtifactService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelArtifactLocator artifactLocator;

    private String modelVersion;
    private Map<String, Integer> userVocab = new HashMap<>();
    private int embeddingDim;
    private Map<String, float[]> itemEmbeddings = Map.of();

    public ModelArtifactService(ModelArtifactLocator artifactLocator) {
        this.artifactLocator = artifactLocator;
    }

    @PostConstruct
    public void loadArtifacts() throws IOException {
        loadFeatureConfig();
        loadItemEmbeddings();
    }

    private void loadFeatureConfig() throws IOException {
        try (InputStream is = artifactLocator.openModel("feature_config.json")) {
            Map<String, Object> config = objectMapper.readValue(is, new TypeReference<>() {});
            this.modelVersion = String.valueOf(config.getOrDefault("model_version", "unknown"));
            this.embeddingDim = readPositiveInt(config.get("embedding_dim"), "embedding_dim");
            this.userVocab = convertToIntMap(config.get("user_vocab"));
        } catch (IllegalStateException e) {
            throw new IllegalStateException("feature_config.json not found at "
                    + artifactLocator.describeModelLocation("feature_config.json")
                    + ". Place model artifacts in src/main/resources/artifacts/twotower/ or set recsys.model.artifacts-dir to an external pipeline output directory.", e);
        }
    }

    private void loadItemEmbeddings() throws IOException {
        try (InputStream is = artifactLocator.openModel("item_embeddings.json")) {
            Map<String, List<Double>> raw = objectMapper.readValue(is, new TypeReference<>() {});
            Map<String, float[]> map = new HashMap<>(raw.size() * 2);
            for (Map.Entry<String, List<Double>> entry : raw.entrySet()) {
                List<Double> values = entry.getValue();
                if (values.size() != embeddingDim) {
                    throw new IllegalStateException("item embedding dimension mismatch for item "
                            + entry.getKey() + ": expected " + embeddingDim + ", got " + values.size());
                }
                float[] vec = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    vec[i] = values.get(i).floatValue();
                }
                map.put(entry.getKey(), vec);
            }
            this.itemEmbeddings = Collections.unmodifiableMap(map);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("item_embeddings.json not found at "
                    + artifactLocator.describeModelLocation("item_embeddings.json")
                    + ". Place model artifacts in src/main/resources/artifacts/twotower/ or set recsys.model.artifacts-dir to an external pipeline output directory.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> convertToIntMap(Object input) {
        if (!(input instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("expected a JSON object for vocab, got: " + (input == null ? "null" : input.getClass()));
        }
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (!(e.getValue() instanceof Number n)) {
                throw new IllegalStateException("vocab value is not numeric for key: " + e.getKey());
            }
            out.put((String) e.getKey(), n.intValue());
        }
        return Map.copyOf(out);
    }

    private int readPositiveInt(Object input, String field) {
        if (!(input instanceof Number n)) {
            throw new IllegalStateException("expected numeric " + field + ", got: " + (input == null ? "null" : input.getClass()));
        }
        int value = n.intValue();
        if (value <= 0) {
            throw new IllegalStateException(field + " must be positive, got: " + value);
        }
        return value;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public Map<String, Integer> getUserVocab() {
        return userVocab;
    }

    public Map<String, float[]> getItemEmbeddings() {
        return itemEmbeddings;
    }
}
