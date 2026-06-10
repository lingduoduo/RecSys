package com.recsys.model.service;

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
        ModelRuntimeProvider provider = new ModelRuntimeProvider(new ModelArtifactLocator("", ""), new com.recsys.config.ABTestConfig());
        try {
            ModelRuntime training = provider.getRuntime("training");
            ModelRuntime test = provider.getRuntime("test");

            assertThat(training.artifactService().getModelVersion()).isEqualTo("dssm-demo-v1");
            assertThat(test.artifactService().getModelVersion()).isEqualTo("dssm-demo-test-v1");
        } finally {
            provider.close();
        }
    }

    @Test
    void getRuntime_loadsIndependentTrainingAndTestVariants(@TempDir Path tmp) throws Exception {
        writeVariantArtifacts(tmp, "training", "demo-training-v1");
        writeVariantArtifacts(tmp, "test", "demo-test-v2");

        ModelRuntimeProvider provider = new ModelRuntimeProvider(new ModelArtifactLocator(tmp.toString(), ""), new com.recsys.config.ABTestConfig());
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

    @Test
    void getRuntime_usesConfiguredModelFile(@TempDir Path tmp) throws Exception {
        writeVariantArtifacts(tmp, "training", "demo-training-v1", "configured_model.onnx");

        ModelRuntimeProvider provider = new ModelRuntimeProvider(
                new ModelArtifactLocator(tmp.toString(), ""),
                new com.recsys.config.ABTestConfig(),
                "configured_model.onnx",
                "classpath",
                "i2vEmb");
        try {
            ModelRuntime training = provider.getRuntime("training");

            assertThat(training.artifactService().getModelVersion()).isEqualTo("demo-training-v1");
            assertThat(training.inferenceService().isReady()).isTrue();
        } finally {
            provider.close();
        }
    }

    private static void writeVariantArtifacts(Path root, String variant, String modelVersion) throws IOException {
        writeVariantArtifacts(root, variant, modelVersion, "dssm_model.onnx");
    }

    private static void writeVariantArtifacts(Path root, String variant, String modelVersion, String modelFile) throws IOException {
        Path variantDir = Files.createDirectories(root.resolve(variant));
        ModelArtifactLocator bundled = new ModelArtifactLocator("", "");

        copyBundledArtifact(bundled, "dssm_model.onnx", variantDir.resolve(modelFile));

        try (InputStream is = bundled.openModel("feature_config.json")) {
            String config = new String(is.readAllBytes()).replace("dssm-demo-v1", modelVersion);
            Files.writeString(variantDir.resolve("feature_config.json"), config);
        }
    }

    private static void copyBundledArtifact(ModelArtifactLocator locator, String fileName, Path target) throws IOException {
        try (InputStream is = locator.openModel(fileName)) {
            Files.copy(is, target);
        }
    }
}
