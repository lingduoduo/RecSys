package com.recsys.config;

import com.recsys.metrics.SplunkHecMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Splunk appender's delivery counters against Spring Boot's auto-configured
 * {@link MeterRegistry}, which is what backs {@code /actuator/prometheus} on the model
 * service.
 *
 * <p>Its own class rather than a method on an existing config: appender wiring has nothing
 * to do with the inference pipeline that {@code ModelRecommendationPipelineConfig} owns.
 */
@Configuration
public class SplunkHecMetricsConfig implements InitializingBean {

    private final MeterRegistry registry;

    public SplunkHecMetricsConfig(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        SplunkHecMetrics.register(registry);
    }
}
