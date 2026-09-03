package com.recsys.application.model;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.recsys.application.model.ModelRuntime;
import com.recsys.application.model.ModelRuntimeProvider;
import com.recsys.application.model.ModelArtifactLocator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRuntimeProviderTest {

    @Test
    void buildsRetrievalAndRankingStagesPerVariant() {
        ModelRuntimeProvider provider = new ModelRuntimeProvider(new ModelArtifactLocator("", ""), new com.recsys.config.ABTestConfig());
        try {
            ModelRuntime runtime = provider.getRuntime("training");
            assertThat(runtime.retrievalStage()).isNotNull();
            assertThat(runtime.rankingStage()).isNotNull();
            assertThat(runtime.artifactService()).isNotNull();
        } finally {
            provider.close();
        }
    }

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

    @Test
    void legacyBundleWarningIsEmittedOncePerNormalizedVariant() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger(ModelRuntimeProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        ModelRuntimeProvider provider = new ModelRuntimeProvider(
                new ModelArtifactLocator("", ""), new com.recsys.config.ABTestConfig());
        try {
            provider.getRuntime(" ");
            provider.getRuntime("training");

            List<String> warnings = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("model_manifest.json"))
                    .toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).contains("training").contains("legacy");
        } finally {
            provider.close();
            logger.detachAppender(appender);
        }
    }

    @Test
    void recallExecutorIsBoundedByConfiguredCapacityAndAbortsOnOverflow() {
        com.recsys.config.ModelServingProperties props = new com.recsys.config.ModelServingProperties();
        props.getRecall().setCoreThreads(3);
        props.getRecall().setQueueCapacity(7);
        ModelRuntimeProvider provider = new ModelRuntimeProvider(
                new ModelArtifactLocator("", ""), new com.recsys.config.ABTestConfig(),
                "dssm_model.onnx", "classpath", "i2vEmb", props,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        java.util.concurrent.ThreadPoolExecutor executor;
        try {
            provider.getRuntime("training");

            assertThat(provider.recallExecutor()).isInstanceOf(java.util.concurrent.ThreadPoolExecutor.class);
            executor = (java.util.concurrent.ThreadPoolExecutor) provider.recallExecutor();
            assertThat(executor.getCorePoolSize()).isEqualTo(3);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(3);
            assertThat(executor.getQueue()).isInstanceOf(java.util.concurrent.ArrayBlockingQueue.class);
            assertThat(executor.getQueue().remainingCapacity() + executor.getQueue().size()).isEqualTo(7);
            assertThat(executor.getRejectedExecutionHandler())
                    .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            provider.close();
        }
        assertThat(executor.isShutdown()).as("close() shuts the recall pool down").isTrue();
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
