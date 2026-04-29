package com.recsys.modelbased.model.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRuntimeProviderTest {

    @Test
    void getRuntime_classpathLoadsBundledTrainingAndTestVariants() {
        ModelRuntimeProvider provider = new ModelRuntimeProvider(new ModelArtifactLocator("", ""), new com.recsys.modelbased.model.config.ABTestConfig());
        try {
            ModelRuntime training = provider.getRuntime("training");
            ModelRuntime test = provider.getRuntime("test");

            assertThat(training.artifactService().getModelVersion()).isEqualTo("demo-model-ratings-v1");
            assertThat(test.artifactService().getModelVersion()).isEqualTo("demo-model-ratings-test-v1");
        } finally {
            provider.close();
        }
    }

    @Test
    void getRuntime_loadsIndependentTrainingAndTestVariants(@TempDir Path tmp) throws Exception {
        writeVariantArtifacts(tmp, "training", "demo-training-v1");
        writeVariantArtifacts(tmp, "test", "demo-test-v2");

        ModelRuntimeProvider provider = new ModelRuntimeProvider(new ModelArtifactLocator(tmp.toString(), ""), new com.recsys.modelbased.model.config.ABTestConfig());
        try {
            ModelRuntime training = provider.getRuntime("training");
            ModelRuntime test = provider.getRuntime("test");

            assertThat(training.artifactService().getModelVersion()).isEqualTo("demo-training-v1");
            assertThat(test.artifactService().getModelVersion()).isEqualTo("demo-test-v2");
            assertThat(training).isNotEqualTo(test);
        } finally {
            provider.close();
        }
    }

    private static void writeVariantArtifacts(Path root, String variant, String modelVersion) throws IOException {
        Path variantDir = Files.createDirectories(root.resolve(variant));
        ModelArtifactLocator bundled = new ModelArtifactLocator("", "");

        copyBundledArtifact(bundled, "user_tower.onnx", variantDir.resolve("user_tower.onnx"));
        copyBundledArtifact(bundled, "item_embeddings.json", variantDir.resolve("item_embeddings.json"));

        try (InputStream is = bundled.openModel("feature_config.json")) {
            String config = new String(is.readAllBytes()).replace("demo-model-ratings-v1", modelVersion);
            Files.writeString(variantDir.resolve("feature_config.json"), config);
        }
    }

    private static void copyBundledArtifact(ModelArtifactLocator locator, String fileName, Path target) throws IOException {
        try (InputStream is = locator.openModel(fileName)) {
            Files.copy(is, target);
        }
    }
}
