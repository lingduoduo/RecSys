package com.recsys.model.config;

import com.recsys.online.event.AsyncEventPublisher;
import com.recsys.online.event.KafkaAsyncEventPublisher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelEventConfigTest {

    private final ModelEventConfig config = new ModelEventConfig();

    @Test
    void enabledWithBootstrap_buildsKafkaPublisher() {
        AsyncEventPublisher pub = config.abExposurePublisher(true, "localhost:9092", "ab_exposures");
        assertThat(pub).isInstanceOf(KafkaAsyncEventPublisher.class);
        pub.close();
    }

    @Test
    void disabled_buildsBaseLogOnlyPublisher() {
        AsyncEventPublisher pub = config.abExposurePublisher(false, "localhost:9092", "ab_exposures");
        assertThat(pub).isExactlyInstanceOf(AsyncEventPublisher.class);   // base, not the Kafka subclass
        pub.close();
    }

    @Test
    void enabledButBlankBootstrap_buildsBaseLogOnlyPublisher() {
        AsyncEventPublisher pub = config.abExposurePublisher(true, "   ", "ab_exposures");
        assertThat(pub).isExactlyInstanceOf(AsyncEventPublisher.class);
        pub.close();
    }
}
