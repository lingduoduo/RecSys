package com.recsys.application.model;
import com.recsys.application.model.ModelArtifactLocator;
import com.recsys.application.model.ModelArtifactService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelArtifactServiceTest {

    private ModelArtifactService service;
    private ModelArtifactLocator locator;

    @BeforeEach
    void setUp() throws IOException {
        locator = new ModelArtifactLocator("", "");
        service = new ModelArtifactService(locator, "");
        service.loadArtifacts();
    }

    @Test
    void modelVersion_matchesBundledConfig() {
        assertThat(service.getModelVersion()).isEqualTo("dssm-demo-v1");
    }

    @Test
    void namedVariant_readsDistinctClasspathBundle() throws IOException {
        ModelArtifactService testVariant = new ModelArtifactService(locator, "test");
        testVariant.loadArtifacts();

        assertThat(testVariant.getModelVersion()).isEqualTo("dssm-demo-test-v1");
        assertThat(testVariant.getItemVocab()).containsKey("1");
    }

    @Test
    void legacyBundleExposesModelBytesAndDefaultContractAfterOneRead() throws IOException {
        CountingModelArtifactLocator countingLocator = new CountingModelArtifactLocator();
        ModelArtifactService legacy = new ModelArtifactService(countingLocator, "training");

        legacy.loadArtifacts();
        byte[] first = legacy.modelBytes();
        first[0] ^= 1;

        assertThat(legacy.resolvedModelFile()).isEqualTo("dssm_model.onnx");
        assertThat(legacy.modelBytes()).isEqualTo(locator.readModelBytes("training", "dssm_model.onnx"));
        assertThat(legacy.modelContract()).isEqualTo(ModelArtifactManifest.ModelContract.legacy());
        assertThat(legacy.isManifestBacked()).isFalse();
        assertThat(countingLocator.modelReads).isEqualTo(1);
    }

    @Test
    void manifestBundleUsesSnapshotFeatureConfigAndAuthoritativeModel(@TempDir Path root) throws IOException {
        Path variantDir = Files.createDirectories(root.resolve("training"));
        byte[] featureConfig = """
                {
                  "model_version": "manifest-v2",
                  "embedding_dim": 2,
                  "user_vocab": { "__UNK__": 0 },
                  "item_vocab": { "7": 0 }
                }
                """.getBytes(StandardCharsets.UTF_8);
        byte[] model = "manifest-model".getBytes(StandardCharsets.UTF_8);
        Files.write(variantDir.resolve("feature_config.json"), featureConfig);
        Files.write(variantDir.resolve("manifest.onnx"), model);
        Files.writeString(variantDir.resolve("model_manifest.json"), """
                {
                  "schema_version": 1,
                  "model_version": "manifest-v2",
                  "model_file": "manifest.onnx",
                  "sha256": {
                    "feature_config.json": "%s",
                    "manifest.onnx": "%s"
                  },
                  "inputs": {
                    "user_id": { "type": "INT64", "rank": 1 },
                    "item_id": { "type": "INT64", "rank": 1 }
                  },
                  "output": { "name": "score", "type": "FLOAT", "rank": 1 }
                }
                """.formatted(sha256(featureConfig), sha256(model)));
        ModelArtifactService manifest = new ModelArtifactService(
                new ModelArtifactLocator(root.toString(), ""), "training", "ignored-legacy.onnx");

        manifest.loadArtifacts();

        assertThat(manifest.getModelVersion()).isEqualTo("manifest-v2");
        assertThat(manifest.getItemVocab()).containsOnlyKeys("7");
        assertThat(manifest.resolvedModelFile()).isEqualTo("manifest.onnx");
        assertThat(manifest.modelBytes()).isEqualTo(model);
        assertThat(manifest.modelContract()).isEqualTo(new ModelArtifactManifest.ModelContract(
                "user_id", "item_id", ModelArtifactManifest.TensorType.INT64, 1,
                "score", ModelArtifactManifest.TensorType.FLOAT, 1));
        assertThat(manifest.isManifestBacked()).isTrue();
    }

    @Test
    void manifestBundleNeverUsesStaleFlatRootItemEmbeddings(@TempDir Path root) throws IOException {
        Path variantDir = Files.createDirectories(root.resolve("training"));
        byte[] featureConfig = """
                {
                  "model_version": "manifest-v3",
                  "embedding_dim": 2,
                  "user_vocab": { "__UNK__": 0 },
                  "item_vocab": {}
                }
                """.getBytes(StandardCharsets.UTF_8);
        byte[] model = "manifest-model".getBytes(StandardCharsets.UTF_8);
        Files.write(variantDir.resolve("feature_config.json"), featureConfig);
        Files.write(variantDir.resolve("manifest.onnx"), model);
        Files.writeString(root.resolve("item_embeddings.json"), "{\"stale\": [0.1, 0.2]}");
        Files.writeString(variantDir.resolve("model_manifest.json"), """
                {
                  "schema_version": 1,
                  "model_version": "manifest-v3",
                  "model_file": "manifest.onnx",
                  "sha256": {
                    "feature_config.json": "%s",
                    "manifest.onnx": "%s"
                  },
                  "inputs": {
                    "user_id": { "type": "INT64", "rank": 1 },
                    "item_id": { "type": "INT64", "rank": 1 }
                  },
                  "output": { "name": "score", "type": "FLOAT", "rank": 1 }
                }
                """.formatted(sha256(featureConfig), sha256(model)));
        ModelArtifactService manifest = new ModelArtifactService(
                new ModelArtifactLocator(root.toString(), ""), "training");

        assertThatThrownBy(manifest::loadArtifacts)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("item_embeddings.json")
                .hasMessageContaining("verified manifest");
    }

    @Test
    void manifestBundleConsumesVerifiedEmbeddingBytesWithoutSecondRead(@TempDir Path root) throws IOException {
        Path variantDir = Files.createDirectories(root.resolve("training"));
        byte[] featureConfig = """
                {
                  "model_version": "manifest-v4",
                  "embedding_dim": 2,
                  "user_vocab": { "__UNK__": 0 },
                  "item_vocab": {}
                }
                """.getBytes(StandardCharsets.UTF_8);
        byte[] model = "manifest-model".getBytes(StandardCharsets.UTF_8);
        byte[] embeddings = "{\"fresh\": [0.3, 0.4]}".getBytes(StandardCharsets.UTF_8);
        Path embeddingsFile = variantDir.resolve("item_embeddings.json");
        Files.write(variantDir.resolve("feature_config.json"), featureConfig);
        Files.write(variantDir.resolve("manifest.onnx"), model);
        Files.write(embeddingsFile, embeddings);
        Files.writeString(root.resolve("item_embeddings.json"), "{\"stale\": [0.1, 0.2]}");
        Files.writeString(variantDir.resolve("model_manifest.json"), """
                {
                  "schema_version": 1,
                  "model_version": "manifest-v4",
                  "model_file": "manifest.onnx",
                  "sha256": {
                    "feature_config.json": "%s",
                    "manifest.onnx": "%s",
                    "item_embeddings.json": "%s"
                  },
                  "inputs": {
                    "user_id": { "type": "INT64", "rank": 1 },
                    "item_id": { "type": "INT64", "rank": 1 }
                  },
                  "output": { "name": "score", "type": "FLOAT", "rank": 1 }
                }
                """.formatted(sha256(featureConfig), sha256(model), sha256(embeddings)));
        ModelArtifactService manifest = new ModelArtifactService(
                new DeleteAfterSnapshotLocator(root, embeddingsFile), "training");

        manifest.loadArtifacts();

        assertThat(manifest.getItemEmbeddings()).containsOnlyKeys("fresh");
        assertThat(embeddingsFile).doesNotExist();
    }

    @Test
    void releasingModelBytesKeepsMetadataButDropsTheCopy() throws IOException {
        ModelArtifactService bundled = new ModelArtifactService(new ModelArtifactLocator("", ""), "training");
        bundled.loadArtifacts();
        assertThat(bundled.modelBytes()).isNotEmpty();

        bundled.releaseModelBytes();

        assertThat(bundled.resolvedModelFile()).isEqualTo("dssm_model.onnx");
        assertThat(bundled.modelContract()).isNotNull();
        assertThat(bundled.isManifestBacked()).isFalse();
        assertThatThrownBy(bundled::modelBytes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("released");
    }

    @Test
    void userVocab_containsKnownUsers() {
        var vocab = service.getUserVocab();
        assertThat(vocab).containsKey("__UNK__");
        assertThat(vocab).containsKey("123");
        assertThat(vocab).containsKey("124");
        assertThat(vocab.get("__UNK__")).isEqualTo(0);
    }

    @Test
    void itemEmbeddings_loadedWithCorrectDimension() {
        assertThat(service.getItemEmbeddings()).isEmpty();
    }

    @Test
    void itemVocab_containsKnownItems() {
        assertThat(service.getItemVocab()).containsKey("1");
    }

    @Test
    void itemEmbeddings_isImmutable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.getItemEmbeddings().put("99", new float[16]))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static final class CountingModelArtifactLocator extends ModelArtifactLocator {
        private int modelReads;

        private CountingModelArtifactLocator() {
            super("", "");
        }

        @Override
        public byte[] readModelBytes(String variant, String fileName) throws IOException {
            modelReads++;
            return super.readModelBytes(variant, fileName);
        }
    }

    private static final class DeleteAfterSnapshotLocator extends ModelArtifactLocator {
        private final Path embeddingsFile;

        private DeleteAfterSnapshotLocator(Path root, Path embeddingsFile) {
            super(root.toString(), "");
            this.embeddingsFile = embeddingsFile;
        }

        @Override
        public java.util.Optional<ModelArtifactSnapshot> loadManifestSnapshot(String variant) {
            java.util.Optional<ModelArtifactSnapshot> snapshot = super.loadManifestSnapshot(variant);
            try {
                Files.delete(embeddingsFile);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return snapshot;
        }
    }
}
