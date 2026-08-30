package com.recsys.config;

import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import com.recsys.metrics.QueueMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@code abExposurePublisher}'s queue metrics against Spring Boot's auto-configured
 * {@link MeterRegistry}, which is what backs {@code /actuator/prometheus} on the model service.
 *
 * <p>This queue, not {@code OnlinePredictionServer}'s {@code async-events} publisher, is the one
 * actually written to: {@code AbExposureLogger} genuinely calls {@code publish()} on it, while
 * {@code async-events} on 7010 has no producer wired up (see {@code OnlineServices.Features}'s
 * dormant, always-null-guarded call, and 18_Fault_Tolerance §8.3). Registering a queue nothing
 * writes to would have produced {@code recsys_queue_*} series that read as a permanently healthy,
 * idle queue rather than as "not instrumented" — indistinguishable from the real thing until
 * someone went looking. Moving the registration here, onto the publisher that is actually on the
 * write path, is what makes the metric mean something.
 */
@Configuration
public class QueueMetricsConfig implements InitializingBean {

    private final MeterRegistry registry;
    private final AsyncEventPublisher abExposurePublisher;

    public QueueMetricsConfig(
            MeterRegistry registry,
            @Qualifier("abExposurePublisher") AsyncEventPublisher abExposurePublisher) {
        this.registry = registry;
        this.abExposurePublisher = abExposurePublisher;
    }

    @Override
    public void afterPropertiesSet() {
        QueueMetrics.register(registry, "ab-exposures", abExposurePublisher);
    }
}
