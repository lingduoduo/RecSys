package com.recsys.online.event;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * {@link AsyncEventPublisher} transport that produces drained event JSON to a fixed Kafka topic.
 * Fire-and-forget: produce failures (synchronous throws or async delivery errors) are logged and
 * never break the drain loop or the request path. The base queue carries value strings only, so
 * every event from this publisher goes to one topic with a null key (round-robin partitioning).
 */
public class KafkaAsyncEventPublisher extends AsyncEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaAsyncEventPublisher.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final Producer<String, String> producer;
    private final String topic;

    public KafkaAsyncEventPublisher(String bootstrapServers, String topic) {
        super();
        Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.producer = new KafkaProducer<>(producerProps(bootstrapServers));
    }

    KafkaAsyncEventPublisher(Producer<String, String> producer, String topic, int queueCapacity, int batchSize) {
        super(queueCapacity, batchSize);
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    private static Properties producerProps(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        return props;
    }

    @Override
    protected void sendBatch(List<String> events) {
        super.sendBatch(events);          // preserve the base drainedCount metric + DEBUG line
        // Guard the construction window: super() starts the drain thread before this subclass's
        // fields are assigned. The queue is empty at construction so this never actually triggers,
        // but the null-check makes the window provably safe.
        if (producer == null) {
            return;
        }
        for (String event : events) {
            try {
                producer.send(new ProducerRecord<>(topic, event), (metadata, ex) -> {
                    if (ex != null) {
                        log.warn("A/B exposure delivery to topic '{}' failed", topic, ex);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("A/B exposure send to topic '{}' threw", topic, e);
            }
        }
    }

    @Override
    public void close() {
        super.close();                    // drains the final batch synchronously through sendBatch
        try {
            producer.close(CLOSE_TIMEOUT); // bounded — don't block shutdown if the broker is down
        } catch (Exception e) {
            log.warn("error closing Kafka exposure producer", e);
        }
    }
}
