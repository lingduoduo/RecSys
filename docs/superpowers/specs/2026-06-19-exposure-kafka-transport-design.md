# A/B Exposure Kafka Transport Design (Follow-up to the model-serving effort)

_Date: 2026-06-19_
_Scope: Port 8080 (`com.recsys.model`) + shared event infra — implement a real Kafka transport for `AsyncEventPublisher` so the A/B exposure events produced in sub-project 2 actually ship to Kafka, config-gated with a log-only fallback._
_Depends on: the A/B reliability work (PR #130, merged) — `AbExposureLogger` + the `abExposurePublisher` bean. Independent of the recall sub-projects._

---

## 1. Problem Statement

Sub-project 2 added `AbExposureLogger`, which builds a structured `ExposureEvent` per served recommendation and publishes it via an `AsyncEventPublisher` bean (`abExposurePublisher`). But **nothing in the compiled service actually produces to Kafka**:

- `AsyncEventPublisher.sendBatch(List<String>)` is a `protected` extension point whose base implementation only `log.debug(...)`s — its Javadoc says "override or subclass to send to Kafka" ([AsyncEventPublisher.java:111-119](../../src/main/java/com/recsys/online/event/AsyncEventPublisher.java)).
- There is **no Kafka transport subclass** anywhere in `src/main`, and **`kafka-clients` is not a compile dependency** (it is present only transitively via `flink-connector-kafka`, which lives in the Maven-excluded `streaming/flink` module).
- The `abExposurePublisher` bean is the base (log-only) `AsyncEventPublisher` ([ModelEventConfig.java](../../src/main/java/com/recsys/model/config/ModelEventConfig.java)).

So A/B exposures are serialized, queued, batched, and drained to a debug log, then discarded — experiments are not analyzable end-to-end. This sub-project implements the transport for the **8080 exposure publisher**.

**Design constraint:** the publisher's internal queue holds **value strings only** (`publish(String)`); topic and key are dropped at the queue boundary. So a transport produces all of a given publisher's events to **one fixed topic**, with no per-event key, unless the shared base is refactored (which would affect port 7010). Per-event routing is out of scope.

---

## 2. Chosen Approach

- **`KafkaAsyncEventPublisher`** — a subclass of `AsyncEventPublisher` that overrides `sendBatch` to produce each event string to a fixed Kafka topic via a `Producer<String,String>`, and overrides `close` to flush + close the producer on shutdown. Errors are swallowed (never break the drain loop). The base class is **not modified** (7010 untouched).
- **Config-gated wiring** — the `abExposurePublisher` bean returns a `KafkaAsyncEventPublisher` only when Kafka is configured (`recsys.events.kafka.enabled=true` + a non-blank `bootstrap-servers`); otherwise it returns the existing log-only base. Local dev, tests, and demos need no broker.
- **`kafka-clients`** added as a compile dependency (version managed by the Spring Boot 3.3.4 BOM).

**Decisions settled in brainstorming:**
- Scope: **8080 exposures only** (the transport is reusable; retrofitting 7010 is future work).
- Activation: **config-gated with a log-only fallback** (not always-on / hard dependency).
- Partition key: **null** (round-robin) — fixed topic per publisher; keying by userId is future work.

**Invariant:** with the feature disabled (default), behavior is identical to today — the base log-only publisher; no Kafka dependency exercised at runtime; the full suite needs no broker.

---

## 3. Architecture

```
AbExposureLogger.log(...)           (SP2, unchanged)
   → publisher.publish(json)        → AsyncEventPublisher bounded queue (String values)
        │
        ▼  drain thread, batched
   KafkaAsyncEventPublisher.sendBatch(batch):
        super.sendBatch(batch)                      // preserve drained-count metric + DEBUG log
        for json in batch:
            try producer.send(new ProducerRecord<>("ab_exposures", json))   // null key
            catch → log + continue                  // never break the drain loop
        │
        ▼
   Kafka topic "ab_exposures"

   shutdown: close() → super.close() (drains final batch) → producer.close(Duration.ofSeconds(5))

ModelEventConfig.abExposurePublisher(enabled, bootstrapServers, topic):
   enabled && bootstrapServers non-blank ? new KafkaAsyncEventPublisher(bootstrapServers, topic)
                                         : new AsyncEventPublisher()    // log-only fallback
```

---

## 4. Components

### 4.1 `KafkaAsyncEventPublisher` (new — `online/event/KafkaAsyncEventPublisher.java`)

Same package as `AsyncEventPublisher` (to extend it and override the `protected sendBatch`).

```java
public class KafkaAsyncEventPublisher extends AsyncEventPublisher {

    // Production: builds a real KafkaProducer (StringSerializer key+value, acks=1, small linger.ms).
    public KafkaAsyncEventPublisher(String bootstrapServers, String topic);

    // Test: inject a Producer (e.g. MockProducer) and the queue sizing.
    KafkaAsyncEventPublisher(Producer<String, String> producer, String topic, int queueCapacity, int batchSize);

    @Override protected void sendBatch(List<String> events);   // super.sendBatch + producer.send per event, errors swallowed
    @Override public void close();                              // super.close() then producer.close(Duration.ofSeconds(5))
}
```

- **Producer config** (production ctor): `bootstrap.servers`, `key.serializer`/`value.serializer = StringSerializer`, `acks=1`, `linger.ms` small (e.g. 20) so the producer batches with the drain cadence. `KafkaProducer` connects lazily, so construction never blocks on an unreachable broker.
- **`sendBatch`**: call `super.sendBatch(events)` first (keeps the base's `drainedCount` accurate and the DEBUG line), then for each event `producer.send(new ProducerRecord<>(topic, event), callback)` where the `Callback` logs async delivery failures (broker down → error arrives after the delivery timeout via the callback, not a throw), all wrapped in a try/catch that logs and continues for **synchronous** throws (serialization, `max.block.ms`/buffer-full, producer-closed). No key (null → round-robin partitioning).
- **`close`**: `super.close()` drains the final batch synchronously through `sendBatch` (so the last events are handed to the producer), then `producer.close(Duration.ofSeconds(5))` — a **bounded** close so shutdown is not blocked if the broker is down.

### 4.2 `ModelEventConfig` (modify — `model/config/ModelEventConfig.java`)

The `abExposurePublisher` bean becomes config-gated (no new class needed — the decision lives in the bean method, which is directly unit-testable):

```java
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
```

`@Bean(destroyMethod = "close")` is unchanged — both publishers implement `close()`. The new properties are documented in `application.yml`/CLAUDE.md (`recsys.events.kafka.enabled`, `recsys.events.kafka.bootstrap-servers`, `recsys.events.kafka.exposure-topic`).

### 4.3 `pom.xml` (modify)

Add `org.apache.kafka:kafka-clients` as a **compile** dependency, version omitted (managed by the Spring Boot 3.3.4 dependency management → kafka-clients ~3.7). `MockProducer` ships in the same artifact and is used by the test.

---

## 5. Data Flow & Behavior

- **Enabled + reachable broker:** exposures flow queue → drain → `producer.send("ab_exposures", json)` → Kafka. On shutdown the final batch is drained and the producer is closed (bounded).
- **Enabled + unreachable broker:** construction + startup succeed (lazy producer); sends buffer then fail after the delivery timeout; callback errors are logged + counted; the request path is unaffected (async). No crash, no startup coupling.
- **Disabled (default):** base log-only publisher; byte-identical to today; no broker needed.

---

## 6. Error Handling

| Condition | Behavior |
|---|---|
| `enabled=false` or blank bootstrap | Base `AsyncEventPublisher` (log-only); no Kafka used |
| Broker unreachable (enabled) | Lazy producer; sends fail after timeout → logged + counted; events dropped; request unaffected |
| `producer.send` throws (serialization, `max.block.ms`, buffer full) | Caught in `sendBatch`; logged; loop continues |
| Bounded queue full (backpressure) | Event dropped at `publish` (base behavior, unchanged) |
| Shutdown with broker down | `producer.close(Duration.ofSeconds(5))` forces a bounded close |

---

## 7. Testing Strategy

### New
- `KafkaAsyncEventPublisherTest` — construct with an injected `org.apache.kafka.clients.producer.MockProducer<String,String>` (autocomplete) via the test ctor: publish N events → `close()` (drains synchronously) → assert `MockProducer.history()` has N `ProducerRecord`s with `topic() == "ab_exposures"`, the right `value()`s, and null keys. A second case — `sendBatch` never lets a producer failure escape: a producer whose `send` **throws synchronously** (a Mockito-mocked `Producer` stubbed to throw) → `sendBatch` swallows it, `close()` completes, no exception propagates, and the remaining events are still attempted (verify the later events are still `send`-attempted).
- `ModelEventConfigTest` — call `abExposurePublisher(true, "localhost:9092", "ab_exposures")` → instance is `KafkaAsyncEventPublisher`; `abExposurePublisher(false, "", "ab_exposures")` → instance is the base `AsyncEventPublisher` (not the subclass). Close both. (The Kafka instance constructs a lazy `KafkaProducer`; no broker needed.)

### Pass unmodified
- `AbExposureLoggerTest` (publisher mocked); the model-serving Spring context-load tests (default `enabled=false` → base publisher); the SP1/SP2/SP3 suites.

### Full-suite
- `mvn test` green with no broker (default disabled). `kafka-clients` on the compile classpath does not change any existing behavior.

---

## 8. Out of Scope

- **Retrofitting port 7010's online event stream onto Kafka** — the other half of the producing gap; a separate (plain-Java) service. Future work; this transport is reusable for it.
- **Per-event topic/key routing** — would require refactoring the shared `AsyncEventPublisher` queue (affects 7010). Not done.
- **Keying exposures by userId** — null key (round-robin) for now.
- **Schema registry / Avro** — JSON strings shipped as-is.
- **The downstream consumer / warehouse join** — the data pipeline's responsibility, not this service.

---

## 9. Files Changed

| File | Change |
|---|---|
| `online/event/KafkaAsyncEventPublisher.java` | New — `AsyncEventPublisher` subclass producing to a fixed Kafka topic; error-swallowing `sendBatch`; bounded flush-on-close |
| `model/config/ModelEventConfig.java` | `abExposurePublisher` bean config-gated (Kafka when enabled+bootstrap, else log-only base) |
| `pom.xml` | Add `org.apache.kafka:kafka-clients` (compile; BOM-managed version) |
| `src/main/resources/application.yml` | Document `recsys.events.kafka.*` properties (defaults: disabled, empty bootstrap, topic `ab_exposures`) |
| `src/test/.../online/event/KafkaAsyncEventPublisherTest.java` | New — `MockProducer` transport + error-swallow tests |
| `src/test/.../model/config/ModelEventConfigTest.java` | New — gating: Kafka vs base by config |
