# SQS Saga Events Design

_Date: 2026-06-25_
_Scope: Saga orchestration (`com.recsys.application.saga`) — publish durable saga transition events to SQS for decoupled workers, audit, and serverless integrations._
_Depends on: existing `SagaEventPublisher` interface and saga transition model._

---

## 1. Problem Statement

The saga orchestration core already has the right extension point:

```java
@FunctionalInterface
public interface SagaEventPublisher {
    SagaEventPublisher NOOP = event -> { };
    void publish(SagaTransitionEvent event);
}
```

`SagaOrchestrators.Base` persists each transition, then calls `publisher.publish(...)`. Comments in `SagaOrchestrators` already state that participant commands should be idempotent because retries and replay are expected on an at-least-once AWS event path.

The missing piece is a concrete AWS event publisher. Today tests and callers use `SagaEventPublisher.NOOP`, so saga state can be persisted in-process, but no durable transition stream exists for:

- audit trails,
- asynchronous participant workers,
- compensation processors,
- serverless integrations,
- operational monitoring outside the JVM.

---

## 2. Chosen Approach

Add `SqsSagaEventPublisher`, a dedicated `SagaEventPublisher` implementation that serializes `SagaTransitionEvent` to JSON and sends it to one SQS queue.

This should not reuse `AsyncEventPublisher` for the first implementation. Saga transitions are already outside the hot recommendation path and are semantically important. Publishing directly lets the caller decide whether a failed event publish should fail the saga step or be logged and tolerated.

Default behavior:

- Persist saga transition first, as today.
- Attempt to publish the transition event to SQS.
- If SQS send fails, throw `SagaException` by default so the caller sees an orchestration reliability problem.
- Provide an optional best-effort mode for demos/tests where publish failures are logged but do not fail the transition.

---

## 3. Architecture

```
SagaOrchestrators.transition(...)
   -> saga.mark(...)
   -> store.saveConditionally(saga)
   -> publish(saga, eventType, stepName)
        -> new SagaTransitionEvent(...)
        -> SqsSagaEventPublisher.publish(event)
             -> ObjectMapper.writeValueAsString(event)
             -> SqsClient.sendMessage(queueUrl, body)
```

For a standard SQS queue, downstream consumers process events at least once. They must use `SagaTransitionEvent.eventId()` as the idempotency key.

For a FIFO queue in a later iteration, `sagaId` can become `MessageGroupId` and `eventId` can become `MessageDeduplicationId`. The first implementation targets standard queues only.

---

## 4. Components

### 4.1 `SqsSagaEventPublisher`

Create `src/main/java/com/recsys/application/saga/SqsSagaEventPublisher.java`.

```java
public final class SqsSagaEventPublisher implements SagaEventPublisher {

    public SqsSagaEventPublisher(SqsClient sqsClient, String queueUrl);

    public SqsSagaEventPublisher(SqsClient sqsClient, String queueUrl,
                                 ObjectMapper objectMapper,
                                 boolean bestEffort);

    @Override
    public void publish(SagaTransitionEvent event);
}
```

Behavior:

- Validate `event` is not null.
- Serialize with Jackson.
- Send `SendMessageRequest` with the configured queue URL and JSON body.
- Add message attributes for common routing fields:
  - `sagaId`
  - `sagaType`
  - `eventType`
  - `status`
  - `correlationId`
- In strict mode (`bestEffort=false`), wrap send/serialization failures in `SagaException`.
- In best-effort mode, log failures and return.

### 4.2 Factory

Create `src/main/java/com/recsys/application/saga/SagaEventPublishers.java`.

```java
public final class SagaEventPublishers {
    public static SagaEventPublisher fromEnvironment();
}
```

Environment variables:

| Env var | Default | Meaning |
|---|---|---|
| `SAGA_EVENTS_SQS_ENABLED` | `false` | Enable SQS publisher |
| `SAGA_EVENTS_SQS_QUEUE_URL` | blank | Queue URL |
| `SAGA_EVENTS_SQS_BEST_EFFORT` | `false` | Log and continue on publish failure |
| `AWS_REGION` | `us-east-1` | AWS SDK region |

If disabled or queue URL is blank, return `SagaEventPublisher.NOOP`.

### 4.3 IAM

The runtime role needs:

```json
{
  "Effect": "Allow",
  "Action": "sqs:SendMessage",
  "Resource": "<saga-events-queue-arn>"
}
```

For EKS, attach this permission to the service account used by whichever service instantiates saga orchestrators.

---

## 5. Event Contract

Message body is the full `SagaTransitionEvent` JSON.

Message attributes duplicate high-cardinality-safe routing fields so simple SQS consumers can filter or route without parsing the body:

| Attribute | Source |
|---|---|
| `sagaId` | `event.sagaId()` |
| `sagaType` | `event.sagaType()` |
| `eventType` | `event.eventType().name()` |
| `status` | `event.status().name()` |
| `correlationId` | `event.correlationId()` |

Idempotency key:

```text
SagaTransitionEvent.eventId()
```

The current event ID format is:

```text
<sagaId>:<eventType>:<stepName-or-saga>
```

Consumers must treat duplicate IDs as replay and skip already-applied work.

---

## 6. Data Flow & Behavior

| Scenario | Behavior |
|---|---|
| SQS disabled | Existing `NOOP` behavior |
| SQS enabled, send succeeds | Transition is persisted and event is sent |
| SQS enabled, strict mode, send fails | Transition remains persisted; publisher throws `SagaException` so caller can retry or alert |
| SQS enabled, best-effort mode, send fails | Transition remains persisted; failure is logged; saga execution continues |
| Duplicate SQS delivery | Consumer deduplicates by `eventId` |

Strict mode deliberately surfaces the split-brain case where state changed but the event did not publish. Best-effort mode is useful for demos where SQS is observability, not orchestration.

---

## 7. Testing Strategy

### New

- `SqsSagaEventPublisherTest`
  - Publishes a transition event and verifies queue URL, JSON body, and message attributes.
  - Serialization/send failure in strict mode throws `SagaException`.
  - Send failure in best-effort mode does not throw.
  - Null event is rejected with `NullPointerException` or `IllegalArgumentException`.

- `SagaEventPublishersTest`
  - Disabled env returns `SagaEventPublisher.NOOP`.
  - Enabled with queue URL returns `SqsSagaEventPublisher`.
  - Enabled with blank queue URL returns `NOOP`.

- `SagaOrchestratorSqsPublisherTest`
  - Use a fake publisher to verify `store.saveConditionally(...)` happens before `publish(...)`.
  - Verify duplicate/replay behavior still relies on persisted saga state, not SQS ordering.

### Existing

- `SagaOrchestratorTest` and `TccSagaOrchestratorTest` continue to use `NOOP` and pass unchanged.
- Full `mvn test` should not require AWS credentials because SQS is disabled by default.

---

## 8. Out of Scope

- SQS FIFO queues.
- Step command dispatch to participant-specific queues.
- A durable outbox table.
- Lambda consumers or Step Functions integration.
- Changing saga persistence semantics.

---

## 9. Files Changed

| File | Change |
|---|---|
| `pom.xml` | Reuse/add AWS SDK SQS dependency |
| `src/main/java/com/recsys/application/saga/SqsSagaEventPublisher.java` | New SQS publisher for saga transition events |
| `src/main/java/com/recsys/application/saga/SagaEventPublishers.java` | New env-driven factory returning SQS or NOOP |
| `k8s/base/configmap.yaml` or service manifest | Add disabled-by-default saga SQS env vars where saga runtime is deployed |
| `src/test/java/com/recsys/application/saga/SqsSagaEventPublisherTest.java` | New unit tests |
| `src/test/java/com/recsys/application/saga/SagaEventPublishersTest.java` | New factory tests |
| `src/test/java/com/recsys/application/saga/SagaOrchestratorSqsPublisherTest.java` | New ordering/contract test |

