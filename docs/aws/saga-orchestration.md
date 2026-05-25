# AWS Saga Orchestration

This project uses eventual consistency for cross-system writes. A request should not hold a
database transaction open while waiting on Kafka, Redis, model artifact refreshes, or other
remote services. Instead, write local state first, emit an idempotent event, and let downstream
services converge.

## Pattern

Use the Saga pattern when a user workflow spans multiple services:

1. Start a saga with a stable `sagaId` and `correlationId`.
2. Persist each state transition before publishing the transition event.
3. Execute participants with an idempotency key of `sagaId + stepName`.
4. Retry transient participant failures.
5. On failure, run compensating actions for completed steps in reverse order.
6. Mark the saga terminal as `COMPLETED` or `FAILED`; terminal sagas are safe to replay.

The JVM implementation lives in `com.recsys.saga`:

- `SagaOrchestrator` executes a durable ordered saga and compensation flow.
- `SagaStateStore` abstracts persistence. The bundled `InMemorySagaStateStore` is for tests
  and local demos; production should back this with DynamoDB or MySQL.
- `SagaEventPublisher` abstracts transition publishing. Production can publish to EventBridge,
  SQS, SNS, or Kafka.
- `AwsStepFunctionsSagaDefinition` renders an Amazon States Language definition for Step
  Functions orchestration.

## AWS Mapping

Recommended production mapping:

| Saga concern | AWS service |
| --- | --- |
| Orchestrator | AWS Step Functions Standard workflow |
| Durable saga state | DynamoDB table keyed by `sagaId` |
| Transition events | EventBridge bus with `sagaId`, `correlationId`, `eventId` |
| Participant commands | Lambda tasks, ECS tasks, or SQS queue consumers |
| Idempotency | DynamoDB conditional write on `sagaId#stepName` |
| Dead letters | SQS DLQ or EventBridge DLQ |
| Observability | CloudWatch metrics on terminal state, compensation count, age |

Step Functions is a good fit when the workflow is known and needs strict ordering. EventBridge
or SQS choreography is still useful inside each participant boundary, but the saga owner should
remain explicit so compensation behavior stays understandable.

## Try/Confirm/Cancel

For workflows that need stronger consistency than plain Saga compensation, use TCC:

| Phase | Responsibility |
| --- | --- |
| Try | Reserve capacity/state with an expiration. Do not make the change externally final. |
| Confirm | Commit a successful reservation. Confirm must be idempotent. |
| Cancel | Release an unconfirmed reservation. Cancel must be idempotent and safe after timeout. |

The JVM implementation is `TccSagaOrchestrator`. It first runs every Try step. If all Try
steps succeed, it Confirms each step in order. If any Try step fails, or if a later Confirm
step fails, it Cancels every tried-but-unconfirmed reservation in reverse order and marks the
saga `FAILED`.

Important TCC rules:

- Use `sagaId + stepName + phase` as the participant idempotency key.
- Give every Try reservation a TTL so abandoned sagas eventually release capacity.
- Never Cancel a step after it has been Confirmed. A failed Confirm after partial commits needs
  reconciliation or a forward recovery event, not a blind rollback.
- Treat Confirm uncertainty carefully. If the participant committed but the acknowledgement was
  lost, retry Confirm by idempotency key until it returns the committed result.

## Recommendation Refresh Example

```java
SagaDefinition definition = new SagaDefinition("recommendation-refresh", List.of(
        SagaStep.awsTask(
                "reserve-recommendation",
                "arn:aws:lambda:us-east-1:123456789012:function:reserveRecommendation",
                "arn:aws:lambda:us-east-1:123456789012:function:releaseRecommendation"
        ),
        SagaStep.awsTask(
                "publish-refresh-event",
                "arn:aws:states:::events:putEvents",
                ""
        )
));

String asl = AwsStepFunctionsSagaDefinition.render(definition);
```

The forward path reserves local recommendation refresh state, then publishes the refresh event.
If event publication fails, Step Functions invokes `releaseRecommendation` and fails the saga.
Downstream consumers update Redis/vector/model-side views asynchronously, so reads converge
without a distributed transaction.

## TCC Recommendation Refresh Example

```java
SagaDefinition definition = new SagaDefinition("recommendation-refresh-tcc", List.of(
        SagaStep.tccAwsTask(
                "reserve-candidate-set",
                "arn:aws:lambda:us-east-1:123456789012:function:tryCandidateSet",
                "arn:aws:lambda:us-east-1:123456789012:function:confirmCandidateSet",
                "arn:aws:lambda:us-east-1:123456789012:function:cancelCandidateSet"
        ),
        SagaStep.tccAwsTask(
                "reserve-feature-refresh",
                "arn:aws:lambda:us-east-1:123456789012:function:tryFeatureRefresh",
                "arn:aws:lambda:us-east-1:123456789012:function:confirmFeatureRefresh",
                "arn:aws:lambda:us-east-1:123456789012:function:cancelFeatureRefresh"
        )
));

String asl = AwsTccStepFunctionsSagaDefinition.render(definition);
```

In this flow, Try reserves candidate-set and feature-refresh work, Confirm makes those
reservations visible, and Cancel releases any reservation that never reached Confirm. This gives
the recommendation service a consistency boundary while still keeping Redis, event streams, and
model refreshes eventually consistent.

## Production Notes

- Store saga state with conditional writes so only one worker can advance a saga step.
- Include `eventId` on every transition and deduplicate consumers by `eventId`.
- Keep compensation semantic, not purely technical. For example, release a reservation or emit a
  cancellation event instead of trying to undo already-observed user behavior.
- Prefer short participant timeouts and retries with exponential backoff. Long waits tie up
  orchestration capacity and slow compensation.
- Alert on sagas stuck outside terminal states longer than the workflow SLO.
