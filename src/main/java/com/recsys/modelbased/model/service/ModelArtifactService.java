package com.recsys.modelbased.model.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.features.RedisEmbeddingStore;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class ModelArtifactService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelArtifactLocator artifactLocator;
    private final String variant;
    private final RedisEmbeddingStore redisItemEmbeddingStore;

    private String modelVersion;
    private Map<String, Integer> userVocab = new HashMap<>();
    private Map<String, Integer> itemVocab = new HashMap<>();
    private int embeddingDim;
    private Map<String, float[]> itemEmbeddings = Map.of();

    public ModelArtifactService(ModelArtifactLocator artifactLocator, String variant) {
        this(artifactLocator, variant, null);
    }

    public ModelArtifactService(ModelArtifactLocator artifactLocator,
                                String variant,
                                RedisEmbeddingStore redisItemEmbeddingStore) {
        this.artifactLocator = artifactLocator;
        this.variant = ModelVariants.trimOrEmpty(variant);
        this.redisItemEmbeddingStore = redisItemEmbeddingStore;
    }

    public void loadArtifacts() throws IOException {
        loadFeatureConfig();
        if (redisItemEmbeddingStore == null) {
            loadItemEmbeddings();
        } else {
            loadItemEmbeddingsFromRedis();
        }
    }

    private void loadFeatureConfig() throws IOException {
        try (InputStream is = artifactLocator.openModel(variant, "feature_config.json")) {
            Map<String, Object> config = objectMapper.readValue(is, new TypeReference<>() {});
            this.modelVersion = String.valueOf(config.getOrDefault("model_version", "unknown"));
            this.embeddingDim = readPositiveInt(config.get("embedding_dim"), "embedding_dim");
            this.userVocab = convertToIntMap(config.get("user_vocab"));
            this.itemVocab = convertToIntMap(config.get("item_vocab"));
        } catch (IllegalStateException e) {
            throw new IllegalStateException("feature_config.json not found at "
                    + artifactLocator.describeModelLocation(variant, "feature_config.json")
                    + ". Place model artifacts under src/main/resources/artifacts/model/<variant>/ or set recsys.model.artifacts-dir to an external model directory.", e);
        }
    }

    private void loadItemEmbeddings() throws IOException {
        try (InputStream is = artifactLocator.openModel(variant, "item_embeddings.json")) {
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
            if (itemVocab.isEmpty()) {
                throw new IllegalStateException("item_embeddings.json not found at "
                        + artifactLocator.describeModelLocation(variant, "item_embeddings.json")
                        + ". Place model artifacts under src/main/resources/artifacts/model/<variant>/ or set recsys.model.artifacts-dir to an external model directory.", e);
            }
            this.itemEmbeddings = Map.of();
        }
    }

    private void loadItemEmbeddingsFromRedis() {
        Map<Integer, float[]> raw = redisItemEmbeddingStore.loadAll();
        if (raw.isEmpty()) {
            throw new IllegalStateException("no item embeddings found in Redis for variant '" + getVariant() + "'");
        }

        Map<String, float[]> map = new HashMap<>(raw.size() * 2);
        for (Map.Entry<Integer, float[]> entry : raw.entrySet()) {
            float[] vec = entry.getValue();
            if (vec.length != embeddingDim) {
                throw new IllegalStateException("item embedding dimension mismatch for item "
                        + entry.getKey() + ": expected " + embeddingDim + ", got " + vec.length);
            }
            map.put(Integer.toString(entry.getKey()), Arrays.copyOf(vec, vec.length));
        }
        this.itemEmbeddings = Collections.unmodifiableMap(map);
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

    public Map<String, Integer> getItemVocab() {
        return itemVocab;
    }

    public Set<String> getAvailableItemIds() {
        if (!itemVocab.isEmpty()) {
            return itemVocab.keySet();
        }
        return itemEmbeddings.keySet();
    }

    public Map<String, float[]> getItemEmbeddings() {
        return itemEmbeddings;
    }

    public String getVariant() {
        return variant;
    }
}
