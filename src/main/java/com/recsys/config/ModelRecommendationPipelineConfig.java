package com.recsys.config;

import com.recsys.application.experiment.ABTestService;
import com.recsys.application.model.OnnxInferencePipeline;
import com.recsys.application.pagination.RecommendationPaginationRuntime;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.application.recommendation.RecommendationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;

/** Spring wiring for the port-8080 model recommendation path. */
@Configuration(proxyBeanMethods = false)
public class ModelRecommendationPipelineConfig {

    @Bean("onnxRecommendationPipeline")
    RecommendationPipeline onnxRecommendationPipeline(
            RecommendationService recommendationService,
            ABTestService abTestService,
            Environment environment,
            MeterRegistry registry
    ) {
        RecommendationPaginationRuntime pagination =
                RecommendationPaginationRuntime.fromEnvironment(
                        environment::getProperty, registry, Clock.systemUTC());
        return new OnnxInferencePipeline(
                recommendationService,
                abTestService,
                pagination.coordinator(),
                pagination.maxCandidates());
    }
}
