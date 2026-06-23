package com.recsys.infrastructure.messaging;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaAsyncEventPublisherTest {

    // Calls the protected sendBatch directly (same package) for a deterministic, single-threaded
    // check — the base's background drain thread is idle here (nothing is published), and the new
    // logic under test is sendBatch/close, not the base queue.

    @Test
    void sendBatch_producesEachEventToTopicWithNullKey() {
        MockProducer<String, String> producer =
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaAsyncEventPublisher pub = new KafkaAsyncEventPublisher(producer, "ab_exposures", 100, 10);

        pub.sendBatch(List.of("{\"e\":1}", "{\"e\":2}"));

        List<ProducerRecord<String, String>> history = producer.history();
        assertThat(history).hasSize(2);
        assertThat(history).allSatisfy(r -> {
            assertThat(r.topic()).isEqualTo("ab_exposures");
            assertThat(r.key()).isNull();
        });
        assertThat(history).extracting(ProducerRecord::value)
                .containsExactlyInAnyOrder("{\"e\":1}", "{\"e\":2}");

        pub.close();
    }

    @Test
    void close_closesProducer() {
        MockProducer<String, String> producer =
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaAsyncEventPublisher pub = new KafkaAsyncEventPublisher(producer, "ab_exposures", 100, 10);

        pub.close();

        assertThat(producer.closed()).isTrue();
    }

    @Test
    void sendBatch_swallowsSendThrows_andAttemptsAllEvents() {
        @SuppressWarnings("unchecked")
        Producer<String, String> producer = mock(Producer.class);
        when(producer.send(any(), any())).thenThrow(new RuntimeException("broker boom"));
        KafkaAsyncEventPublisher pub = new KafkaAsyncEventPublisher(producer, "ab_exposures", 100, 10);

        assertThatCode(() -> pub.sendBatch(List.of("{\"e\":1}", "{\"e\":2}"))).doesNotThrowAnyException();

        verify(producer, times(2)).send(any(), any());   // both attempted despite the throw

        pub.close();
    }
}
