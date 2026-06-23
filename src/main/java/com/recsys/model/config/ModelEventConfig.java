package com.recsys.model.config;

import com.recsys.infrastructure.messaging.AsyncEventPublisher;
import com.recsys.infrastructure.messaging.KafkaAsyncEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelEventConfig {

    /**
     * Bounded, fire-and-forget publisher for A/B exposure events. Ships to Kafka when configured
     * ({@code recsys.events.kafka.enabled} + a non-blank bootstrap), otherwise the log-only base
     * (local dev / tests / demo need no broker). Closed on context shutdown.
     */
    @Bean(destroyMethod = "close")
    public AsyncEventPublisher abExposurePublisher(
            @Value("${recsys.events.kafka.enabled:false}") boolean enabled,
            @Value("${recsys.events.kafka.bootstrap-servers:}") String bootstrapServers,
            @Value("${recsys.events.kafka.exposure-topic:ab_exposures}") String topic) {
        if (enabled && !bootstrapServers.isBlank()) {
            return new KafkaAsyncEventPublisher(bootstrapServers, topic);
        }
        return new AsyncEventPublisher();
    }
}
