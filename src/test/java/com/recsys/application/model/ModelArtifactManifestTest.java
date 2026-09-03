package com.recsys.application.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelArtifactManifestTest {

    private static final String VARIANT = "training";
    private static final String MODEL_FILE = "dssm_model.onnx";
    private static final byte[] MODEL_BYTES = "verified-model".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FEATURE_BYTES = """
            {
              "model_version": "dssm-v1",
              "embedding_dim": 2,
              "user_vocab": { "__UNK__": 0 },
              "item_vocab": { "1": 0 }
            }
            """.getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path artifactRoot;

    @Test
    void validManifestReturnsVerifiedSnapshot() throws IOException {
        Path variantDir = writeArtifacts();
        byte[] companion = "metadata".getBytes(StandardCharsets.UTF_8);
        Files.write(variantDir.resolve("labels.txt"), companion);
        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE, Map.of(
                "feature_config.json", sha256(FEATURE_BYTES),
                MODEL_FILE, sha256(MODEL_BYTES),
                "labels.txt", sha256(companion)
        ), validInputs(), validOutput());

        ModelArtifactSnapshot snapshot = locator().loadManifestSnapshot(VARIANT).orElseThrow();

        assertThat(snapshot.featureConfig()).isEqualTo(FEATURE_BYTES);
        assertThat(snapshot.model()).isEqualTo(MODEL_BYTES);
        assertThat(snapshot.modelFile()).isEqualTo(MODEL_FILE);
        assertThat(snapshot.modelVersion()).isEqualTo("dssm-v1");
        assertThat(snapshot.contract()).isEqualTo(new ModelArtifactManifest.ModelContract(
                "user_id", "item_id", ModelArtifactManifest.TensorType.INT64, 1,
                "score", ModelArtifactManifest.TensorType.FLOAT, 1));
    }

    @Test
    void absentManifestReturnsEmptyForLegacyBundle() throws IOException {
        writeArtifacts();

        assertThat(locator().loadManifestSnapshot(VARIANT)).isEmpty();
    }

    @Test
    void malformedPresentManifestDoesNotFallBack() throws IOException {
        Path variantDir = writeArtifacts();
        Files.writeString(variantDir.resolve("model_manifest.json"), "{not-json");
        Files.write(artifactRoot.resolve(MODEL_FILE), "flat-fallback".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model_manifest.json")
                .hasMessageContaining("training");
    }

    @Test
    void rejectsUnknownTopLevelFieldsInsteadOfIgnoringThem() throws IOException {
        // Version 1 has exactly six top-level fields. Silently ignoring an unknown one would
        // let a typo'd "sha256s" or a stray "notes" key pass validation with the check it
        // meant to configure never applied; the runbook documents this rejection.
        Path variantDir = writeArtifacts();
        String checksums = validChecksums().entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\": \"" + e.getValue() + "\"")
                .reduce((l, r) -> l + "," + r).orElse("");
        Files.writeString(variantDir.resolve("model_manifest.json"), """
                {
                  "schema_version": 1,
                  "model_version": "dssm-v1",
                  "model_file": "%s",
                  "sha256": {%s},
                  "inputs": {%s},
                  "output": {%s},
                  "notes": "published by hand"
                }
                """.formatted(MODEL_FILE, checksums, validInputs(), validOutput()));

        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model_manifest.json");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws IOException {
        Path variantDir = writeArtifacts();
        writeManifest(variantDir, 2, "dssm-v1", MODEL_FILE, validChecksums(), validInputs(), validOutput());

        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema_version")
                .hasMessageContaining("2");
    }

    @Test
    void rejectsTraversalAndAbsoluteModelPaths() throws IOException {
        Path variantDir = writeArtifacts();

        for (String unsafe : new String[]{"../x.onnx", "/x.onnx", "nested/x.onnx", "nested\\x.onnx"}) {
            writeManifest(variantDir, 1, "dssm-v1", unsafe,
                    Map.of("feature_config.json", sha256(FEATURE_BYTES), unsafe, sha256(MODEL_BYTES)),
                    validInputs(), validOutput());

            assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                    .as("model_file %s", unsafe)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("model_file")
                    .hasMessageContaining(unsafe);
        }
    }

    @Test
    void rejectsSymlinkThatEscapesVariantDirectory() throws IOException {
        Path variantDir = writeArtifacts();
        Path outsideModel = Files.write(artifactRoot.resolveSibling("outside-model.onnx"), MODEL_BYTES);
        Path modelPath = variantDir.resolve(MODEL_FILE);
        Files.delete(modelPath);
        try {
            Files.createSymbolicLink(modelPath, outsideModel);
        } catch (UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("symbolic links are not supported: " + e.getMessage());
        } catch (java.nio.file.FileSystemException e) {
            Assumptions.abort("symbolic links are not available: " + e.getMessage());
        }
        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE, validChecksums(), validInputs(), validOutput());

        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MODEL_FILE)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsNonRegularArtifactFile() throws IOException {
        Path variantDir = writeArtifacts();
        Path modelPath = variantDir.resolve(MODEL_FILE);
        Files.delete(modelPath);
        Files.createDirectory(modelPath);
        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE, validChecksums(), validInputs(), validOutput());

        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MODEL_FILE)
                .hasMessageContaining("not a regular file");
    }

    @Test
    void rejectsMissingAndMismatchedChecksums() throws IOException {
        Path variantDir = writeArtifacts();
        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE,
                Map.of("feature_config.json", sha256(FEATURE_BYTES)), validInputs(), validOutput());

        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MODEL_FILE);

        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE,
                Map.of("feature_config.json", "0".repeat(64), MODEL_FILE, sha256(MODEL_BYTES)),
                validInputs(), validOutput());

        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("feature_config.json")
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsManifestFeatureVersionMismatch() throws IOException {
        Path variantDir = writeArtifacts();
        writeManifest(variantDir, 1, "dssm-v2", MODEL_FILE, validChecksums(), validInputs(), validOutput());

        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model_version")
                .hasMessageContaining("dssm-v2")
                .hasMessageContaining("dssm-v1");
    }

    @Test
    void rejectsMissingUnexpectedAndDuplicateInputNames() throws IOException {
        Path variantDir = writeArtifacts();
        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE, validChecksums(),
                "\"user_id\": {\"type\": \"INT64\", \"rank\": 1}", validOutput());
        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .hasMessageContaining("item_id");

        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE, validChecksums(),
                validInputs() + ", \"context\": {\"type\": \"INT64\", \"rank\": 1}", validOutput());
        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .hasMessageContaining("context");

        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE, validChecksums(),
                validInputs() + ", \"user_id\": {\"type\": \"INT64\", \"rank\": 1}", validOutput());
        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .hasMessageContaining("duplicate")
                .hasMessageContaining("user_id");
    }

    @Test
    void rejectsUnsupportedTensorTypesAndInvalidRanks() throws IOException {
        Path variantDir = writeArtifacts();
        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE, validChecksums(),
                "\"user_id\": {\"type\": \"FLOAT\", \"rank\": 1},"
                        + "\"item_id\": {\"type\": \"INT64\", \"rank\": 1}", validOutput());
        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .hasMessageContaining("user_id")
                .hasMessageContaining("INT64");

        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE, validChecksums(),
                "\"user_id\": {\"type\": \"INT64\", \"rank\": 0},"
                        + "\"item_id\": {\"type\": \"INT64\", \"rank\": 1}", validOutput());
        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .hasMessageContaining("user_id")
                .hasMessageContaining("rank");

        writeManifest(variantDir, 1, "dssm-v1", MODEL_FILE, validChecksums(), validInputs(),
                "\"name\": \"score\", \"type\": \"INT64\", \"rank\": 1");
        assertThatThrownBy(() -> locator().loadManifestSnapshot(VARIANT))
                .hasMessageContaining("score")
                .hasMessageContaining("FLOAT");
    }

    private ModelArtifactLocator locator() {
        return new ModelArtifactLocator(artifactRoot.toString(), "");
    }

    private Path writeArtifacts() throws IOException {
        Path variantDir = Files.createDirectories(artifactRoot.resolve(VARIANT));
        Files.write(variantDir.resolve("feature_config.json"), FEATURE_BYTES);
        Files.write(variantDir.resolve(MODEL_FILE), MODEL_BYTES);
        return variantDir;
    }

    private Map<String, String> validChecksums() {
        return Map.of(
                "feature_config.json", sha256(FEATURE_BYTES),
                MODEL_FILE, sha256(MODEL_BYTES)
        );
    }

    private static String validInputs() {
        return "\"user_id\": {\"type\": \"INT64\", \"rank\": 1},"
                + "\"item_id\": {\"type\": \"INT64\", \"rank\": 1}";
    }

    private static String validOutput() {
        return "\"name\": \"score\", \"type\": \"FLOAT\", \"rank\": 1";
    }

    private static void writeManifest(
            Path variantDir,
            int schemaVersion,
            String modelVersion,
            String modelFile,
            Map<String, String> checksums,
            String inputs,
            String output
    ) throws IOException {
        String checksumJson = checksums.entrySet().stream()
                .map(entry -> "\"" + entry.getKey().replace("\\", "\\\\") + "\": \"" + entry.getValue() + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        Files.writeString(variantDir.resolve("model_manifest.json"), """
                {
                  "schema_version": %d,
                  "model_version": "%s",
                  "model_file": "%s",
                  "sha256": {%s},
                  "inputs": {%s},
                  "output": {%s}
                }
                """.formatted(schemaVersion, modelVersion, modelFile.replace("\\", "\\\\"),
                checksumJson, inputs, output));
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
