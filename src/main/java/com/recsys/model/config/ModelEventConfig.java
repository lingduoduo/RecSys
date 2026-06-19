package com.recsys.model.config;

import com.recsys.online.event.AsyncEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelEventConfig {

    /** Bounded, fire-and-forget publisher for A/B exposure events. Closed on context shutdown. */
    @Bean(destroyMethod = "close")
    public AsyncEventPublisher abExposurePublisher() {
        return new AsyncEventPublisher();
    }
}
