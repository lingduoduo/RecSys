package com.recsys.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelServingPropertiesConfiguration.class);

    @Test
    void bindsModelServingProperties() {
        contextRunner
                .withPropertyValues(
                        "recsys.model.onnx.intra-op-threads=3",
                        "recsys.model.onnx.inter-op-threads=4",
                        "recsys.model.onnx.execution-mode=PARALLEL",
                        "recsys.model.recall.core-threads=5",
                        "recsys.model.recall.queue-capacity=512",
                        "recsys.model.recall.timeout-ms=350")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    ModelServingProperties properties = context.getBean(ModelServingProperties.class);
                    assertThat(properties.getOnnx().getIntraOpThreads()).isEqualTo(3);
                    assertThat(properties.getOnnx().getInterOpThreads()).isEqualTo(4);
                    assertThat(properties.getOnnx().getExecutionMode())
                            .isEqualTo(ModelServingProperties.ExecutionMode.PARALLEL);
                    assertThat(properties.getRecall().getCoreThreads()).isEqualTo(5);
                    assertThat(properties.getRecall().getQueueCapacity()).isEqualTo(512);
                    assertThat(properties.getRecall().getTimeoutMs()).isEqualTo(350);
                });
    }

    @Test
    void bindsZeroCoreThreadsToTheComputedDefault() {
        contextRunner
                .withPropertyValues("recsys.model.recall.core-threads=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ModelServingProperties.class).getRecall().getCoreThreads())
                            .isEqualTo(Math.max(1, Runtime.getRuntime().availableProcessors() * 2));
                });
    }

    @Test
    void rejectsZeroAndNegativeThreadQueueAndTimeoutValuesAtStartup() {
        List.of(
                        "recsys.model.onnx.intra-op-threads=0",
                        "recsys.model.onnx.intra-op-threads=-1",
                        "recsys.model.onnx.inter-op-threads=0",
                        "recsys.model.onnx.inter-op-threads=-1",
                        "recsys.model.recall.core-threads=-1",
                        "recsys.model.recall.queue-capacity=0",
                        "recsys.model.recall.queue-capacity=-1",
                        "recsys.model.recall.timeout-ms=0",
                        "recsys.model.recall.timeout-ms=-1")
                .forEach(invalidProperty -> contextRunner
                        .withPropertyValues(invalidProperty)
                        .run(context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure()).isNotNull();
                        }));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ModelServingProperties.class)
    static class ModelServingPropertiesConfiguration {
    }
}
