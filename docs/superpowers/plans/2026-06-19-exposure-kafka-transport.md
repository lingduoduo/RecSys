# A/B Exposure Kafka Transport — Implementation Plan (model-serving follow-up)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship SP2's A/B exposure events to Kafka by implementing the `AsyncEventPublisher.sendBatch` transport hook as a `KafkaAsyncEventPublisher`, and wiring port 8080's `abExposurePublisher` bean to it — config-gated, with the log-only base as fallback.

**Architecture:** A `KafkaAsyncEventPublisher` subclass overrides `sendBatch` to produce each drained event string to a fixed Kafka topic (null key) and `close` to flush+close the producer (bounded). `ModelEventConfig` chooses the Kafka transport vs the log-only base by config. The base class and port 7010 are untouched.

**Tech Stack:** Java 17, Spring Boot 3.3.4, Maven, JUnit 5, AssertJ, Mockito, `kafka-clients` (+ its `MockProducer` for tests).

## Global Constraints

- Java 17, Maven. `mvn test -Dtest=<Class>` runs one class. Branch off `main`.
- **Disabled by default = byte-identical to today** (base log-only publisher; no broker needed; full suite runs with no Kafka).
- **Do NOT modify** `AsyncEventPublisher` (the base — 7010 uses it), `AbExposureLogger`, or the ONNX/recall code.
- Fixed topic per publisher, **null key** (round-robin); fire-and-forget — a producer failure (sync throw OR async delivery error) is logged and **never** breaks the drain loop or the request.
- Gating properties: `recsys.events.kafka.enabled` (default `false`), `recsys.events.kafka.bootstrap-servers` (default empty), `recsys.events.kafka.exposure-topic` (default `ab_exposures`). Transport active only when `enabled && bootstrap-servers` non-blank.
- `KafkaAsyncEventPublisher` lives in `com.recsys.online.event` (to extend `AsyncEventPublisher` and override its `protected sendBatch`).

---

### Task 1: `KafkaAsyncEventPublisher` transport

**Files:**
- Modify: `pom.xml` (add `kafka-clients` compile dependency)
- Create: `src/main/java/com/recsys/online/event/KafkaAsyncEventPublisher.java`
- Test: `src/test/java/com/recsys/online/event/KafkaAsyncEventPublisherTest.java`

**Interfaces:**
- Consumes: `AsyncEventPublisher` (public `()` and package-private `(int queueCapacity, int batchSize)` ctors; `protected void sendBatch(List<String>)`; `public void close()`; `publish(String)`).
- Produces: `KafkaAsyncEventPublisher(String bootstrapServers, String topic)` (production); `KafkaAsyncEventPublisher(Producer<String,String> producer, String topic, int queueCapacity, int batchSize)` (test).

- [ ] **Step 1: Add the `kafka-clients` dependency**

In `pom.xml`, inside the top-level `<dependencies>` block (around line 85-170, next to the other Spring/runtime deps), add:

```xml
    <dependency>
      <groupId>org.apache.kafka</groupId>
      <artifactId>kafka-clients</artifactId>
    </dependency>
```

No `<version>` — it is managed by the Spring Boot 3.3.4 dependency management. Verify:

Run: `mvn -q -DskipTests dependency:resolve 2>&1 | grep kafka-clients` (or just `mvn -q -DskipTests compile`).
Expected: resolves a version (~3.7.x). If Maven errors with "dependencies.dependency.version is missing", add `<version>3.7.1</version>` to the dependency and re-run.

- [ ] **Step 2: Write the failing test**

```java
package com.recsys.online.event;

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
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn test -Dtest=KafkaAsyncEventPublisherTest`
Expected: COMPILATION FAILURE — `KafkaAsyncEventPublisher` does not exist.

- [ ] **Step 4: Create the class**

```java
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
        } catch (RuntimeException e) {
            log.warn("error closing Kafka exposure producer", e);
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=KafkaAsyncEventPublisherTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add pom.xml \
        src/main/java/com/recsys/online/event/KafkaAsyncEventPublisher.java \
        src/test/java/com/recsys/online/event/KafkaAsyncEventPublisherTest.java
git commit -m "feat: add KafkaAsyncEventPublisher transport for AsyncEventPublisher

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Config-gate the `abExposurePublisher` bean

**Files:**
- Modify: `src/main/java/com/recsys/model/config/ModelEventConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/recsys/model/config/ModelEventConfigTest.java`

**Interfaces:**
- Consumes: `KafkaAsyncEventPublisher(String bootstrapServers, String topic)` (Task 1); `AsyncEventPublisher()`.
- Produces: `ModelEventConfig.abExposurePublisher(boolean enabled, String bootstrapServers, String topic)` → `AsyncEventPublisher` (the bean method, directly unit-testable).

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=ModelEventConfigTest`
Expected: COMPILATION FAILURE — `abExposurePublisher(boolean, String, String)` does not exist (current bean takes no args).

- [ ] **Step 3: Update `ModelEventConfig`** — replace the file body:

```java
package com.recsys.model.config;

import com.recsys.online.event.AsyncEventPublisher;
import com.recsys.online.event.KafkaAsyncEventPublisher;
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
```

- [ ] **Step 4: Document the properties in `application.yml`** — add an `events` block under the existing `recsys:` root (alongside `ab-test`, `model`, etc.):

```yaml
  events:
    kafka:
      enabled: ${RECSYS_EVENTS_KAFKA_ENABLED:false}
      bootstrap-servers: ${RECSYS_EVENTS_KAFKA_BOOTSTRAP_SERVERS:}
      exposure-topic: ${RECSYS_EVENTS_KAFKA_EXPOSURE_TOPIC:ab_exposures}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=ModelEventConfigTest`
Expected: PASS (3 tests). The `enabled` case constructs a real `KafkaProducer` (lazy — no broker connection at construction) and closes it bounded.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/model/config/ModelEventConfig.java \
        src/main/resources/application.yml \
        src/test/java/com/recsys/model/config/ModelEventConfigTest.java
git commit -m "feat: config-gate A/B exposure publisher to Kafka transport (log-only fallback)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Full build + test suite**

Run: `mvn test`
Expected: BUILD SUCCESS — all tests pass with NO Kafka broker (the publisher defaults to disabled → base log-only). Adding `kafka-clients` to the compile classpath changes no existing behavior; the model-serving Spring context loads with the default-disabled exposure publisher.

- [ ] **Step 2: Confirm the base publisher and 7010 are untouched**

Run: `git diff --stat main..HEAD -- src/main/java/com/recsys/online/event/AsyncEventPublisher.java`
Expected: no output (the base class is unchanged).

- [ ] **Step 3: No commit** (verification only).

---

## Self-Review

**Spec coverage:**
- §4.1 `KafkaAsyncEventPublisher` (sendBatch super-then-produce with callback + try/catch, null key, fixed topic, bounded close) → Task 1.
- §4.2 `ModelEventConfig` gated bean → Task 2.
- §4.3 `pom.xml` `kafka-clients` (compile, BOM-managed) → Task 1 Step 1.
- §5/§6 behavior + error handling (disabled→base; sync throw swallowed; async failure via callback; bounded close; lazy producer no startup coupling) → Task 1 (transport + tests), Task 2 (gating).
- §7 testing (MockProducer history + null key; sync-throw swallow; gating Kafka-vs-base) → Tasks 1, 2.
- §9 files changed → all covered; `application.yml` → Task 2 Step 4.

**Placeholder scan:** none — every step has concrete code/commands. The pom version is intentionally omitted (BOM-managed) with an explicit pinned-version fallback if resolution fails.

**Type consistency:**
- `KafkaAsyncEventPublisher(String, String)` and `(Producer<String,String>, String, int, int)` — defined Task 1, used Task 1 tests + Task 2 (`abExposurePublisher`).
- `ModelEventConfig.abExposurePublisher(boolean, String, String)` → `AsyncEventPublisher` — defined Task 2, asserted Task 2 test.
- `sendBatch(List<String>)` (protected, same-package call) / `close()` overrides — Task 1.
- Gating properties `recsys.events.kafka.{enabled,bootstrap-servers,exposure-topic}` consistent across Task 2 (`@Value` defaults) and `application.yml`.
