package com.recsys.config;

import com.recsys.application.experiment.ABTestService;
import com.recsys.application.experiment.AbExposureLogger;
import com.recsys.application.model.OnnxInferencePipeline;
import com.recsys.application.pagination.RecommendationPaginationRuntime;
import com.recsys.application.recommendation.ProtectedRecommendationPipeline;
import com.recsys.application.recommendation.RecommendationPipeline;
import com.recsys.application.recommendation.RecommendationService;
import com.recsys.loadshed.LoadShedder;
import com.recsys.metrics.InferenceMetricsService;
import com.recsys.ratelimit.ModelRateLimiter;
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
            MeterRegistry registry,
            ModelRateLimiter rateLimiter,
            LoadShedder loadShedder,
            InferenceMetricsService metrics,
            AbExposureLogger exposureLogger
    ) {
        RecommendationPaginationRuntime pagination =
                RecommendationPaginationRuntime.fromEnvironment(
                        environment::getProperty, registry, Clock.systemUTC());
        RecommendationPipeline onnx = new OnnxInferencePipeline(
                recommendationService,
                abTestService,
                pagination.coordinator(),
                pagination.maxCandidates());
        // The canonical POST /api/recommend reaches this bean via /v2/recommend, so the guards
        // belong here rather than in the controller. /v2/sequential/recommend is deliberately
        // NOT wrapped — see the spec.
        return new ProtectedRecommendationPipeline(
                onnx, rateLimiter, loadShedder, metrics, abTestService, exposureLogger);
    }
}
