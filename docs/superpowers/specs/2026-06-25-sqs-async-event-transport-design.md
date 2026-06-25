# SQS Async Event Transport Design

_Date: 2026-06-25_
_Scope: Shared event infrastructure (`com.recsys.infrastructure.messaging`) — add an AWS SQS transport for `AsyncEventPublisher` so services can use an AWS-native queue for durable, decoupled background events._
_Depends on: existing `AsyncEventPublisher` and `KafkaAsyncEventPublisher` abstractions._

---

## 1. Problem Statement

The codebase has a good local decoupling abstraction for best-effort event publication:

- `AsyncEventPublisher` provides a bounded in-memory queue, a drain thread, batched delivery, and a `protected sendBatch(List<String>)` transport hook.
- `KafkaAsyncEventPublisher` proves the transport model by sending drained JSON strings to one Kafka topic.
- `ModelEventConfig` currently chooses Kafka for A/B exposures when `recsys.events.kafka.*` is enabled, otherwise the base log-only publisher is used.

There is no SQS dependency, SQS client, queue URL config, or AWS queue transport. Deployments that want AWS-native decoupling must run Kafka even for workloads where SQS is a better operational fit: low-volume exposure logging, feature-view events, and at-least-once background processing.

**Design constraint:** the base queue stores serialized value strings only. A single publisher instance sends every drained message to one fixed destination. Per-message routing, FIFO group IDs, and event-specific deduplication require either payload inspection or a base-publisher refactor and are out of scope for the first SQS transport.

---

## 2. Chosen Approach

Add `SqsAsyncEventPublisher`, a sibling of `KafkaAsyncEventPublisher`, that extends `AsyncEventPublisher` and sends drained JSON strings to a configured SQS queue URL with AWS SDK v2.

The transport is config-gated. Existing Kafka and log-only behavior remains unchanged by default:

1. If SQS is enabled and has a non-blank queue URL, use `SqsAsyncEventPublisher`.
2. Else if Kafka is enabled and has a non-blank bootstrap server, use `KafkaAsyncEventPublisher`.
3. Else use the base log-only `AsyncEventPublisher`.

This order makes SQS an explicit deployment choice while preserving local development and test behavior.

**Invariant:** no user-facing request fails because SQS is slow or unavailable. `publish(String)` remains non-blocking; failures in `sendBatch` are logged and dropped.

---

## 3. Architecture

```
producer service code
   -> publisher.publish(json)
        -> AsyncEventPublisher bounded ArrayBlockingQueue
             -> drain thread batches messages
                  -> SqsAsyncEventPublisher.sendBatch(batch)
                       -> SendMessageBatch(queueUrl, entries)
                       -> log failed entries and request exceptions
```

Configuration chooses one transport per publisher bean:

```
SQS configured?   -> SqsAsyncEventPublisher
Kafka configured? -> KafkaAsyncEventPublisher
otherwise         -> AsyncEventPublisher
```

For standard queues, messages may be delivered at least once and out of order. Consumers must be idempotent. This matches the repo's existing event posture: best-effort publication on the serving side, durable at-least-once processing downstream.

---

## 4. Components

### 4.1 `SqsAsyncEventPublisher`

Create `src/main/java/com/recsys/infrastructure/messaging/SqsAsyncEventPublisher.java`.

```java
public class SqsAsyncEventPublisher extends AsyncEventPublisher {

    public SqsAsyncEventPublisher(SqsClient sqsClient, String queueUrl);

    SqsAsyncEventPublisher(SqsClient sqsClient, String queueUrl,
                           int queueCapacity, int batchSize);

    @Override
    protected void sendBatch(List<String> events);

    @Override
    public void close();
}
```

Behavior:

- `sendBatch` calls `super.sendBatch(events)` first to preserve base drained metrics.
- Split drained events into SQS batches of at most 10 entries because SQS `SendMessageBatch` accepts 10 messages per call.
- Each entry uses a deterministic batch-local ID such as `msg-0`, `msg-1`; this is only the SQS batch entry ID, not a business event ID.
- For standard queues, set only `id` and `messageBody`.
- Catch `RuntimeException` and AWS SDK exceptions per batch; log and continue.
- If the response contains failed entries, log each failed entry code/message. Do not retry inside the serving process.
- `close` delegates to `super.close()` to drain the local queue. The injected `SqsClient` is not closed by default because AWS clients may be shared by Spring beans; if production wiring creates a dedicated client, the bean configuration owns closing it.

### 4.2 Configuration

Extend `ModelEventConfig` with SQS properties for the exposure publisher:

```yaml
recsys:
  events:
    sqs:
      enabled: ${RECSYS_EVENTS_SQS_ENABLED:false}
      queue-url: ${RECSYS_EVENTS_SQS_QUEUE_URL:}
      region: ${AWS_REGION:us-east-1}
```

Bean selection:

```java
if (sqsEnabled && !sqsQueueUrl.isBlank()) {
    return new SqsAsyncEventPublisher(sqsClient, sqsQueueUrl);
}
if (kafkaEnabled && !bootstrapServers.isBlank()) {
    return new KafkaAsyncEventPublisher(bootstrapServers, topic);
}
return new AsyncEventPublisher();
```

Use `software.amazon.awssdk.services.sqs.SqsClient` with default credentials provider behavior. In EKS this relies on IRSA; local development can use the default AWS profile or keep SQS disabled.

### 4.3 Dependencies

Add AWS SDK v2 SQS:

```xml
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>sqs</artifactId>
  <version>${aws.sdk.version}</version>
</dependency>
```

Add an `aws.sdk.version` property. Use one version consistently for future AWS transports.

---

## 5. Data Flow & Behavior

| Scenario | Behavior |
|---|---|
| SQS disabled | Existing Kafka/log-only behavior remains unchanged |
| SQS enabled with queue URL | Drained event JSON is sent via `SendMessageBatch` |
| Queue unreachable or AWS credentials invalid | Startup still succeeds if the client can be constructed; sends fail asynchronously in the drain thread; request path is unaffected |
| Local queue full | Base publisher drops the event and increments dropped count |
| Batch has partial SQS failures | Successful entries are accepted by SQS; failed entries are logged and dropped |
| Shutdown | Local queue drains once through `super.close()` |

---

## 6. Error Handling

This transport intentionally does not do in-process retries. The serving process is not the durable buffer; SQS is. Retrying inside the drain thread would increase memory pressure and can block shutdown under AWS outages.

Failures are handled as follows:

- Local backpressure: drop at `publish` via existing bounded queue behavior.
- AWS request exception: log queue URL suffix, exception class, and batch size.
- SQS partial failure: log failed entry IDs and sender-fault flag.
- Serialization errors: callers already publish JSON strings; malformed JSON is still a valid SQS body and is a consumer concern.

Metrics can be added later, but the first implementation should at least expose the existing `AsyncEventPublisher.Snapshot` through service ops endpoints where already available.

---

## 7. Testing Strategy

### New

- `SqsAsyncEventPublisherTest`
  - Publish 12 events, close publisher, verify the fake `SqsClient` receives two `SendMessageBatchRequest`s with 10 and 2 entries.
  - Verify each request uses the configured queue URL and preserves message bodies.
  - Fake a partial failed batch response; assert `close()` does not throw and later batches are still attempted.
  - Fake `sendMessageBatch` throwing a runtime exception; assert `close()` does not throw.

- `ModelEventConfigTest`
  - SQS enabled + queue URL returns `SqsAsyncEventPublisher`.
  - SQS disabled + Kafka enabled returns `KafkaAsyncEventPublisher`.
  - Both disabled returns exactly the base `AsyncEventPublisher`.
  - SQS enabled with blank queue URL falls through to Kafka/log-only.

### Existing

- `KafkaAsyncEventPublisherTest` remains unchanged.
- `AbExposureLoggerTest` remains transport-agnostic because it mocks `AsyncEventPublisher`.
- Full `mvn test` should pass with no AWS credentials and no queue because SQS is disabled by default.

---

## 8. Out of Scope

- FIFO queues, `MessageGroupId`, and content-based deduplication.
- Per-event queue routing or payload-derived SQS attributes.
- A downstream SQS consumer.
- Replacing Kafka for the Flink online feature pipeline.
- In-process retry queues or local disk spooling.

---

## 9. Files Changed

| File | Change |
|---|---|
| `pom.xml` | Add AWS SDK SQS dependency and version property |
| `src/main/java/com/recsys/infrastructure/messaging/SqsAsyncEventPublisher.java` | New SQS transport for `AsyncEventPublisher` |
| `src/main/java/com/recsys/config/ModelEventConfig.java` | Config-gate SQS before Kafka for `abExposurePublisher` |
| `src/main/resources/application.yml` | Document `recsys.events.sqs.*` defaults |
| `src/test/java/com/recsys/infrastructure/messaging/SqsAsyncEventPublisherTest.java` | New unit tests for batching and failure swallowing |
| `src/test/java/com/recsys/config/ModelEventConfigTest.java` | Extend transport-selection tests |

