# SQS Online Events Design

_Date: 2026-06-25_
_Scope: Port 7010 (`OnlinePredictionServer`) — route online feature-view/background events through the shared SQS-capable `AsyncEventPublisher` abstraction._
_Depends on: `2026-06-25-sqs-async-event-transport-design.md`._

---

## 1. Problem Statement

The online serving process creates an `AsyncEventPublisher` directly:

```java
AsyncEventPublisher asyncEventPublisher = new AsyncEventPublisher();
```

That publisher is injected into:

- `OnlineServices.Features`, which emits a `feature_view` JSON event after a successful `/online/features` response.
- `OnlineOpsService`, which exposes publisher queue/drain/drop stats in the ops response.

Because the publisher is the base implementation, these events are queued, drained, logged at DEBUG, and discarded. That is fine for local demos, but it leaves no durable handoff for AWS deployments that want to decouple online serving from downstream analytics, auditing, or serverless processors.

This spec wires the online server to the same SQS transport choice used by model-serving exposure events, while keeping recommendation reads synchronous and low-latency.

---

## 2. Chosen Approach

Introduce a small factory for plain Java services:

```java
public final class AsyncEventPublisherFactory {
    public static AsyncEventPublisher fromEnvironment(String purpose);
}
```

`OnlinePredictionServer` calls:

```java
AsyncEventPublisher asyncEventPublisher =
        AsyncEventPublisherFactory.fromEnvironment("ONLINE_EVENTS");
```

The factory uses environment variables because port 7010 is an Armeria main class, not a Spring Boot application:

| Env var | Default | Meaning |
|---|---|---|
| `ONLINE_EVENTS_SQS_ENABLED` | `false` | Enable SQS transport |
| `ONLINE_EVENTS_SQS_QUEUE_URL` | blank | SQS queue URL |
| `AWS_REGION` | `us-east-1` | AWS SDK region |
| `ONLINE_EVENTS_KAFKA_ENABLED` | `false` | Optional Kafka fallback |
| `ONLINE_EVENTS_KAFKA_BOOTSTRAP_SERVERS` | blank | Kafka bootstrap servers |
| `ONLINE_EVENTS_KAFKA_TOPIC` | `online_events` | Kafka topic |

Selection order matches the shared transport spec: SQS, then Kafka, then log-only.

---

## 3. Architecture

```
GET /online/features
   -> OnlineServices.Features.render(...)
   -> afterSuccess(...)
   -> asyncEventPublisher.publish(feature_view_json)
        -> local bounded queue
        -> SQS SendMessageBatch to ONLINE_EVENTS_SQS_QUEUE_URL

GET /online/ops
   -> OnlineOpsService
   -> asyncEventPublisher.snapshot()
```

Only side-channel events are queued. The recommendation APIs keep their current synchronous Redis/recall behavior.

---

## 4. Components

### 4.1 `AsyncEventPublisherFactory`

Create `src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisherFactory.java`.

```java
public final class AsyncEventPublisherFactory {
    public static AsyncEventPublisher fromEnvironment(String envPrefix);

    static AsyncEventPublisher from(String envPrefix,
                                    Map<String, String> env,
                                    Function<Region, SqsClient> sqsClientFactory);
}
```

Use uppercase env prefixes. For `envPrefix = "ONLINE_EVENTS"` read:

- `ONLINE_EVENTS_SQS_ENABLED`
- `ONLINE_EVENTS_SQS_QUEUE_URL`
- `ONLINE_EVENTS_KAFKA_ENABLED`
- `ONLINE_EVENTS_KAFKA_BOOTSTRAP_SERVERS`
- `ONLINE_EVENTS_KAFKA_TOPIC`

The factory creates:

- `SqsAsyncEventPublisher` when SQS is enabled with a queue URL.
- `KafkaAsyncEventPublisher` when Kafka is enabled with bootstrap servers.
- `AsyncEventPublisher` otherwise.

The package-private `from(...)` overload exists only to make unit tests deterministic. Production code calls `fromEnvironment(...)`, which delegates to `System.getenv()` and a real `SqsClient` factory.

### 4.2 `OnlinePredictionServer`

Replace direct construction:

```java
AsyncEventPublisher asyncEventPublisher = new AsyncEventPublisher();
```

with:

```java
AsyncEventPublisher asyncEventPublisher =
        AsyncEventPublisherFactory.fromEnvironment("ONLINE_EVENTS");
```

Shutdown behavior remains unchanged: the existing shutdown hook calls `asyncEventPublisher.close()`.

### 4.3 Kubernetes

Add optional env vars to `k8s/base/online-serving.yaml`, defaulting disabled:

```yaml
- name: ONLINE_EVENTS_SQS_ENABLED
  value: "false"
- name: ONLINE_EVENTS_SQS_QUEUE_URL
  value: ""
```

EKS overlays can set the real queue URL and IRSA service account permissions.

---

## 5. Event Contract

`OnlineServices.Features.featureViewEvent` remains the event body:

```json
{
  "eventId": "uuid",
  "userId": 42,
  "eventType": "feature_view",
  "window": "last_hour",
  "eventTimeMillis": 1760000000000,
  "source": "online-features"
}
```

Consumers should use `eventId` as the idempotency key. Standard SQS queues can deliver duplicate messages.

---

## 6. Data Flow & Behavior

| Scenario | Behavior |
|---|---|
| Local development | Env vars absent; base log-only publisher |
| EKS with SQS enabled | Feature-view events are delivered to SQS without blocking HTTP responses |
| SQS outage | Publisher logs send failures; `/online/features` still returns the feature snapshot |
| Local queue full | `feature_view` event is dropped; recommendation and feature reads are unaffected |
| Shutdown | Existing shutdown hook drains local queue once |

---

## 7. Testing Strategy

### New

- `AsyncEventPublisherFactoryTest`
  - No env map produces base `AsyncEventPublisher`.
  - SQS enabled with queue URL produces `SqsAsyncEventPublisher` using a fake `SqsClient` factory.
  - SQS enabled with blank queue URL falls through.
  - Kafka enabled with bootstrap servers produces `KafkaAsyncEventPublisher`.

- `OnlinePredictionServer` construction-level test
  - Extract publisher creation behind a package-private static method if needed.
  - Verify the online server uses the factory instead of direct `new AsyncEventPublisher()`.

### Existing

- `OnlinePredictionServerIntegrationTest` should pass with no AWS config because SQS is disabled by default.
- `OnlineOpsServiceTest` continues to assert publisher snapshot shape; transport is still an `AsyncEventPublisher`.

---

## 8. Out of Scope

- Sending every recommendation impression to SQS.
- Moving the Kafka -> Flink -> Redis feature pipeline to SQS.
- Adding SQS consumers or Lambda processors.
- Changing `/online/recommendation` latency behavior.
- FIFO ordering for per-user events.

---

## 9. Files Changed

| File | Change |
|---|---|
| `src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisherFactory.java` | New env-driven publisher factory |
| `src/main/java/com/recsys/api/online/OnlinePredictionServer.java` | Use factory for online event publisher |
| `k8s/base/online-serving.yaml` | Add disabled-by-default SQS env vars |
| `k8s/eks/kustomization.yaml` or patch file | Optional queue URL/IRSA patch for EKS deployments |
| `src/test/java/com/recsys/infrastructure/messaging/AsyncEventPublisherFactoryTest.java` | New transport-selection tests |
| `src/test/java/com/recsys/api/online/OnlinePredictionServerIntegrationTest.java` | Ensure default path still starts without AWS config |
