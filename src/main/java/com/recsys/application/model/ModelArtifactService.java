package com.recsys.application.model;
import com.recsys.application.experiment.ModelVariants;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.infrastructure.redis.RedisEmbeddingStore;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class ModelArtifactService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DEFAULT_MODEL_FILE = "dssm_model.onnx";
    private final ModelArtifactLocator artifactLocator;
    private final String variant;
    private final String legacyModelFile;
    private final RedisEmbeddingStore redisItemEmbeddingStore;

    private String modelVersion;
    private String resolvedModelFile;
    private byte[] modelBytes;
    private ModelArtifactManifest.ModelContract modelContract;
    private boolean manifestBacked;
    private Map<String, Integer> userVocab = new HashMap<>();
    private Map<String, Integer> itemVocab = new HashMap<>();
    private int embeddingDim;
    private Map<String, float[]> itemEmbeddings = Map.of();

    public ModelArtifactService(ModelArtifactLocator artifactLocator, String variant) {
        this(artifactLocator, variant, DEFAULT_MODEL_FILE, null);
    }

    public ModelArtifactService(ModelArtifactLocator artifactLocator,
                                String variant,
                                RedisEmbeddingStore redisItemEmbeddingStore) {
        this(artifactLocator, variant, DEFAULT_MODEL_FILE, redisItemEmbeddingStore);
    }

    public ModelArtifactService(ModelArtifactLocator artifactLocator,
                                String variant,
                                String legacyModelFile) {
        this(artifactLocator, variant, legacyModelFile, null);
    }

    public ModelArtifactService(ModelArtifactLocator artifactLocator,
                                String variant,
                                String legacyModelFile,
                                RedisEmbeddingStore redisItemEmbeddingStore) {
        this.artifactLocator = artifactLocator;
        this.variant = ModelVariants.trimOrEmpty(variant);
        this.legacyModelFile = Strings.orDefault(legacyModelFile, DEFAULT_MODEL_FILE);
        this.redisItemEmbeddingStore = redisItemEmbeddingStore;
    }

    public void loadArtifacts() throws IOException {
        Optional<ModelArtifactSnapshot> snapshot = artifactLocator.loadManifestSnapshot(variant);
        ModelArtifactSnapshot verifiedSnapshot = null;
        if (snapshot.isPresent()) {
            verifiedSnapshot = snapshot.get();
            loadFeatureConfig(verifiedSnapshot.featureConfig());
            this.resolvedModelFile = verifiedSnapshot.modelFile();
            this.modelBytes = verifiedSnapshot.model();
            this.modelContract = verifiedSnapshot.contract();
            this.manifestBacked = true;
        } else {
            loadFeatureConfig(null);
            this.resolvedModelFile = legacyModelFile;
            this.modelBytes = artifactLocator.readModelBytes(variant, legacyModelFile);
            this.modelContract = ModelArtifactManifest.ModelContract.legacy();
            this.manifestBacked = false;
        }
        if (redisItemEmbeddingStore == null) {
            if (verifiedSnapshot == null) {
                loadItemEmbeddings();
            } else {
                loadVerifiedItemEmbeddings(verifiedSnapshot);
            }
        } else {
            loadItemEmbeddingsFromRedis();
        }
    }

    private void loadFeatureConfig(byte[] verifiedFeatureConfig) throws IOException {
        try {
            Map<String, Object> config;
            if (verifiedFeatureConfig != null) {
                config = objectMapper.readValue(verifiedFeatureConfig, new TypeReference<>() {});
            } else {
                try (InputStream is = artifactLocator.openModel(variant, "feature_config.json")) {
                    config = objectMapper.readValue(is, new TypeReference<>() {});
                }
            }
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

    private void loadVerifiedItemEmbeddings(ModelArtifactSnapshot snapshot) throws IOException {
        Optional<byte[]> verifiedEmbeddings = snapshot.companion("item_embeddings.json");
        if (verifiedEmbeddings.isEmpty()) {
            if (itemVocab.isEmpty()) {
                throw new IllegalStateException("item_embeddings.json not found in verified manifest snapshot "
                        + "for variant '" + getVariant() + "'");
            }
            this.itemEmbeddings = Map.of();
            return;
        }

        try (InputStream is = new java.io.ByteArrayInputStream(verifiedEmbeddings.get())) {
            loadItemEmbeddings(is);
        }
    }

    private void loadItemEmbeddings(InputStream is) throws IOException {
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
    }

    private void loadItemEmbeddingsFromRedis() {
        Map<Integer, float[]> raw = redisItemEmbeddingStore.loadAll();
        if (raw.isEmpty()) {
            throw new IllegalStateException("no item embeddings found in Redis for variant '" + getVariant() + "'");
        }

        // Re-key from Integer to String directly, reusing the float[] references from loadAll().
        // Arrays.copyOf() was removed: the vectors are read-only after load and not exposed for
        // mutation, so a defensive copy doubles the live heap (and triggers a Full GC on large
        // embedding stores) with no safety benefit.
        Map<String, float[]> map = new HashMap<>(raw.size() * 2);
        for (Map.Entry<Integer, float[]> entry : raw.entrySet()) {
            float[] vec = entry.getValue();
            if (vec.length != embeddingDim) {
                throw new IllegalStateException("item embedding dimension mismatch for item "
                        + entry.getKey() + ": expected " + embeddingDim + ", got " + vec.length);
            }
            map.put(Integer.toString(entry.getKey()), vec);
        }
        this.itemEmbeddings = Collections.unmodifiableMap(map);
    }

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

    public String resolvedModelFile() {
        ensureModelLoaded();
        return resolvedModelFile;
    }

    public byte[] modelBytes() {
        ensureModelLoaded();
        return modelBytes.clone();
    }

    public ModelArtifactManifest.ModelContract modelContract() {
        ensureModelLoaded();
        return modelContract;
    }

    public boolean isManifestBacked() {
        ensureModelLoaded();
        return manifestBacked;
    }

    private void ensureModelLoaded() {
        if (modelBytes == null) {
            throw new IllegalStateException("model artifacts have not been loaded");
        }
    }
}
