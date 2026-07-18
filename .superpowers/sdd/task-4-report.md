# Task 4 Report: Relay Workers and Reliable Kafka/SQS Adapters

## Status

DONE

## RED

1. Added focused tests for relay acknowledgement/failure lifecycle, exact claimed lease owner/version propagation, Kafka idempotent producer configuration and callback acknowledgement, SQS acknowledgement and stable identity attributes, and legacy producer hardening.
2. Ran:
   `mvn test -Dtest=OutboxRelayTest,KafkaOutboxDeliveryAdapterTest,SqsOutboxDeliveryAdapterTest,KafkaAsyncEventPublisherTest`
   Result: expected test-compilation failure with 21 errors because relay/adapters/receipt/repository port were absent and legacy `producerProps` was private.
3. During self-review added a cross-cycle concurrency regression test and ran:
   `mvn test -Dtest=OutboxRelayTest`
   Result: expected failure (`expected: 0 but was: 1`) proving a second cycle could claim while the first send still occupied capacity.

## GREEN

- Added `OutboxDeliveryAdapter` and immutable `DeliveryReceipt`.
- Added an `OutboxRepository` application port and made `MySqlOutboxRepository` implement it without changing its owner-aware SQL mutations.
- Added `OutboxRelay` with destination dispatch, asynchronous broker-ack terminal transitions, retry/dead-letter policy, cycle timeout, and a semaphore bounding in-flight sends across relay cycles.
- Every delivered/rescheduled/dead mutation uses the exact `leaseOwner` and `version` from the claimed `OutboxEvent`; the configured worker name is used only for claiming.
- Added Kafka adapter preserving the outbox partition key and payload; its stage completes only in Kafka's callback.
- Added SQS adapter preserving payload and supplying stable `eventId`, `aggregateId`, and `eventType` message attributes; its stage completes only after `SqsAsyncClient` acknowledgement.
- Hardened both Kafka producers with idempotence, `acks=all`, unbounded client retries bounded by delivery timeout, request timeout, and max-in-flight 5.

Fresh verification:

- `mvn test -q -Dtest=OutboxRelayTest,KafkaOutboxDeliveryAdapterTest,SqsOutboxDeliveryAdapterTest,KafkaAsyncEventPublisherTest` — exit 0, 14 tests.
- `mvn package -q -DskipTests` — exit 0.
- `git diff --check` — exit 0.

## Self-review

- Confirmed broker send invocation alone cannot mark an event delivered.
- Confirmed synchronous adapter throws, exceptional stages, and timeouts all follow retry/dead-letter policy.
- Confirmed terminal callbacks release relay capacity even if repository mutation throws.
- Confirmed repeated `runOnce` calls cannot exceed configured in-flight capacity.
- Confirmed adapter code performs no repository mutation.
- Confirmed stable Kafka key/payload and SQS identity metadata are not regenerated.
- Confirmed only Task 4 source/test files are staged; existing reports and `src/main/resources/artifacts` remain unstaged.

## Commit

- `66a66f9 feat(outbox): relay events with idempotent delivery`

## Concerns

- Docker-tagged MySQL integration tests were not run; Task 4 focused tests and a compile/package verification passed.
- Existing `KafkaAsyncEventPublisherTest` intentionally logs two WARN stack traces while asserting send exceptions are swallowed; the test exits successfully.

## Final Review Fix: Deadline-capable delivery attempts

- Added `DeliveryAttempt`, which exposes both broker completion and idempotent cancellation confirmation. The confirmation contract means the transport attempt is no longer live.
- Relay deadlines now cancel the real attempt and wait for confirmed settlement before retry/dead transition and capacity release. Delivery completion and deadline cancellation share one atomic terminal selector, preventing late-callback double transitions.
- Kafka cancellation delegates to its native send `Future`; where Kafka cannot immediately cancel an accepted record, confirmation waits for the native callback, whose existing producer delivery timeout bounds liveness. SQS cancellation delegates to its native `CompletableFuture` and confirms settlement from that future.
- `markDelivered`, `reschedule`, and `markDead` boolean results are checked. A false result is reported as an observed transition conflict and the relay performs no compensating write that could overwrite a newer lease owner.
- Added regressions for a never-completing delivery deadline, cancellation confirmation ordering before retry/capacity release, late callback races exactly once, transition conflicts, and native Kafka/SQS cancellation settlement.

Final RED evidence:

- Focused test compilation failed with 9 missing-`DeliveryAttempt` errors before the new contract was added.
- A subsequent fresh run exposed the Kafka mock's non-immediate native cancellation and an observer-test synchronization race; the tests were corrected to wait for native settlement and the observer signal respectively.

Final verification:

- `mvn test -q -Dtest=OutboxRelayTest,KafkaOutboxDeliveryAdapterTest,SqsOutboxDeliveryAdapterTest,KafkaAsyncEventPublisherTest` — exit 0, 24 tests.
- `mvn package -q -DskipTests` — exit 0.
- `git diff --check` — exit 0.

## Review Fix

- Removed relay-side `CompletableFuture.orTimeout`: capacity now remains held until the original broker stage completes, so a late acknowledgement cannot overlap a resend or be dead-lettered because of wrapper timeouts.
- Moved every delivered/rescheduled/dead repository transition to a relay-owned bounded executor. Broker callbacks and the JDK delay scheduler never perform repository I/O.
- Added explicit terminal-failure observation, per-event synchronous failure isolation, and `AutoCloseable` lifecycle behavior that stops claims and drains live sends/terminal work up to the configured deadline before forcing executor shutdown.
- Added regressions for late acknowledgement/no overlapping resend, callback-thread isolation, terminal repository failure visibility, synchronous failure batch continuation, and shutdown draining.
- Review verification: `mvn test -q -Dtest=OutboxRelayTest,KafkaOutboxDeliveryAdapterTest,SqsOutboxDeliveryAdapterTest,KafkaAsyncEventPublisherTest` — exit 0, 19 tests; `mvn package -q -DskipTests` — exit 0; `git diff --check` — exit 0.
