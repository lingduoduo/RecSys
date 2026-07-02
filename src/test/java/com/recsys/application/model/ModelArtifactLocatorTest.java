package com.recsys.application.model;
import com.recsys.application.model.ModelArtifactLocator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelArtifactLocatorTest {

    // ---- model artifacts ----

    @Test
    void openModel_classpath_readsContent() throws IOException {
        var locator = new ModelArtifactLocator("", "");
        try (InputStream is = locator.openModel("feature_config.json")) {
            assertThat(new String(is.readAllBytes())).contains("model_version");
        }
    }

    @Test
    void openModel_externalDir_readsContent(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.json"), "{\"key\":\"value\"}");
        var locator = new ModelArtifactLocator(tmp.toString(), "");
        try (InputStream is = locator.openModel("config.json")) {
            assertThat(new String(is.readAllBytes())).contains("key");
        }
    }

    @Test
    void openModel_missingFile_throwsIllegalState() {
        var locator = new ModelArtifactLocator("", "");
        assertThatThrownBy(() -> locator.openModel("does_not_exist.json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readModelBytes_matchesOpenModel() throws IOException {
        var locator = new ModelArtifactLocator("", "");
        byte[] direct = locator.readModelBytes("feature_config.json");
        try (InputStream is = locator.openModel("feature_config.json")) {
            assertThat(direct).isEqualTo(is.readAllBytes());
        }
    }

    @Test
    void describeModelLocation_classpath_returnsClasspathPrefix() {
        var locator = new ModelArtifactLocator("", "");
        assertThat(locator.describeModelLocation("feature_config.json"))
                .isEqualTo("classpath:artifacts/model/training/feature_config.json");
    }

    @Test
    void describeModelLocation_externalDir_returnsAbsolutePath(@TempDir Path tmp) {
        var locator = new ModelArtifactLocator(tmp.toString(), "");
        assertThat(locator.describeModelLocation("feature_config.json"))
                .startsWith(tmp.toAbsolutePath().toString());
    }

    @Test
    void openModel_variantExternalDir_prefersVariantSubdirectory(@TempDir Path tmp) throws IOException {
        Path trainingDir = Files.createDirectories(tmp.resolve("training"));
        Files.writeString(trainingDir.resolve("config.json"), "{\"slot\":\"training\"}");
        Files.writeString(tmp.resolve("config.json"), "{\"slot\":\"flat\"}");

        var locator = new ModelArtifactLocator(tmp.toString(), "");
        try (InputStream is = locator.openModel("training", "config.json")) {
            assertThat(new String(is.readAllBytes())).contains("training");
        }
    }

    @Test
    void describeModelLocation_variantClasspath_returnsGenericPath() {
        var locator = new ModelArtifactLocator("", "");
        assertThat(locator.describeModelLocation("training", "feature_config.json"))
                .startsWith("classpath:artifacts/model/training/feature_config.json");
    }

    // ---- spark artifacts ----

    @Test
    void openSpark_classpath_readsContent() throws IOException {
        var locator = new ModelArtifactLocator("", "");
        try (InputStream is = locator.openSpark("als_model_metadata.json")) {
            assertThat(new String(is.readAllBytes())).isNotBlank();
        }
    }

    @Test
    void openSpark_externalDir_readsContent(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("model_meta.json"), "{\"type\":\"als\"}");
        var locator = new ModelArtifactLocator("", tmp.toString());
        try (InputStream is = locator.openSpark("model_meta.json")) {
            assertThat(new String(is.readAllBytes())).contains("als");
        }
    }

    @Test
    void openSpark_missingFile_throwsIllegalState() {
        var locator = new ModelArtifactLocator("", "");
        assertThatThrownBy(() -> locator.openSpark("nonexistent_spark.json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void describeSparkLocation_classpath_returnsClasspathPrefix() {
        var locator = new ModelArtifactLocator("", "");
        assertThat(locator.describeSparkLocation("als_model_metadata.json"))
                .isEqualTo("classpath:artifacts/pyspark/als_model_metadata.json");
    }

    @Test
    void resolveSparkPath_externalDir_returnsResolvedPath(@TempDir Path tmp) {
        var locator = new ModelArtifactLocator("", tmp.toString());
        Path resolved = locator.resolveSparkPath("als/metadata");
        assertThat(resolved).isEqualTo(tmp.resolve("als").resolve("metadata").normalize());
    }

    @Test
    void resolveSparkPath_classpathExploded_returnsExistingFilesystemPath() {
        // When resources are exploded on disk (as in mvn test runs), a real, existing
        // filesystem Path is returned — without the jar-hostile getFile() call.
        var locator = new ModelArtifactLocator("", "");
        Path resolved = locator.resolveSparkPath("als_model_metadata.json");
        assertThat(resolved).exists();
        assertThat(resolved.getFileName().toString()).isEqualTo("als_model_metadata.json");
    }

    @Test
    void resolveSparkPath_missingClasspathResource_throwsIllegalState() {
        var locator = new ModelArtifactLocator("", "");
        assertThatThrownBy(() -> locator.resolveSparkPath("nonexistent_spark_dir"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recsys.spark.artifacts-dir");
    }

    // ---- whitespace-only overrides are treated as classpath ----

    @Test
    void blankExternalDir_fallsBackToClasspath() throws IOException {
        var locator = new ModelArtifactLocator("   ", "");
        try (InputStream is = locator.openModel("feature_config.json")) {
            assertThat(is).isNotNull();
        }
    }
}
