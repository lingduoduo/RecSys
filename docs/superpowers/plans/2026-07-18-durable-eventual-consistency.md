# Durable Eventual Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver durable at-least-once API and saga events, bounded read-your-writes semantics, deterministic atomic Redis updates, consistency metrics, and automated reconciliation.

**Architecture:** MySQL stores outbox events and production saga state. Destination-specific relay adapters publish leased rows to Kafka or SQS; Flink applies versioned updates to Redis, while signed consistency tokens allow primary-read lineage checks and a CronJob reconciles missing applications.

**Tech Stack:** Java 17, JDBC/HikariCP, MySQL 8.4, Flyway, Kafka clients, AWS SDK SQS, Apache Flink, Redis/Lettuce Lua, Armeria, Micrometer/Prometheus, JUnit 5, Mockito, Testcontainers, Kubernetes Kustomize.

## Global Constraints

- Accepted durable API events must be committed to MySQL before the response succeeds.
- Production saga state and its transition event must commit in the same MySQL transaction.
- Delivery is at least once; stable event IDs and deterministic consumers absorb duplicates.
- Consistency tokens expire after 24 hours and wait at most two seconds before returning `202 Accepted` with `Retry-After: 1`.
- Ordinary tokenless reads retain the existing replica-and-cache path.
- Reconciliation scans the previous 24 hours by default and has a configurable maximum batch size.
- Prometheus labels must remain bounded; never label metrics with user IDs, event IDs, or Redis keys.
- Preserve all existing operator UIDs and maximum parallelism unless a task explicitly introduces a new operator.
- Use TDD for every behavior change: run the focused test and observe the expected failure before production edits.

---

## File Structure

- `src/main/resources/db/migration/V2__create_event_outbox_and_sagas.sql` — durable schema and claim/reconciliation indexes.
- `src/main/java/com/recsys/domain/outbox/*` — transport-neutral outbox records, status, destination, and retry policy.
- `src/main/java/com/recsys/infrastructure/persistence/TransactionalMySql.java` — writable JDBC transaction boundary with its own non-read-only pool.
- `src/main/java/com/recsys/infrastructure/outbox/MySqlOutboxRepository.java` — insert, lease, acknowledge, retry, dead-letter, and reconciliation queries.
- `src/main/java/com/recsys/infrastructure/saga/MySqlSagaStateStore.java` — optimistic saga persistence plus atomic transition enqueue.
- `src/main/java/com/recsys/application/outbox/*` — relay orchestration and Kafka/SQS adapters.
- `src/main/java/com/recsys/application/consistency/*` — token codec and primary Redis lineage waiter.
- `src/main/java/com/recsys/metrics/ConsistencyMetrics.java` — bounded Micrometer instruments.
- `src/main/java/com/recsys/application/reconciliation/*` — reconciliation use case and command entry point.
- Existing online service, Flink job, Redis routing, server wiring, and Kubernetes manifests receive narrowly scoped integration changes.

---

### Task 1: Durable Schema and Writable Transaction Boundary

**Files:**
- Create: `src/main/resources/db/migration/V2__create_event_outbox_and_sagas.sql`
- Create: `src/main/java/com/recsys/infrastructure/persistence/TransactionalMySql.java`
- Test: `src/test/java/com/recsys/infrastructure/persistence/TransactionalMySqlTest.java`
- Test: `src/test/java/com/recsys/infrastructure/persistence/OutboxSchemaMySqlIntegrationTest.java`

**Interfaces:**
- Produces: `<T> T TransactionalMySql.inTransaction(SqlTransaction<T> work)` and `Connection openConnection()`.
- Produces tables `event_outbox` and `saga_instance` consumed by Tasks 2–4 and 9.

- [ ] **Step 1: Write the failing transaction tests**

```java
@Test void commitsSuccessfulWork() throws Exception {
    TransactionalMySql mysql = fixture.writableClient();
    mysql.inTransaction(c -> { insertProbe(c, "committed"); return null; });
    assertThat(fixture.probes()).containsExactly("committed");
}

@Test void rollsBackFailedWork() {
    TransactionalMySql mysql = fixture.writableClient();
    assertThatThrownBy(() -> mysql.inTransaction(c -> {
        insertProbe(c, "rolled-back");
        throw new IllegalStateException("boom");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(fixture.probes()).isEmpty();
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn test -Dtest=TransactionalMySqlTest,OutboxSchemaMySqlIntegrationTest -DexcludedGroups=load`

Expected: compilation fails because `TransactionalMySql` and migration V2 do not exist.

- [ ] **Step 3: Add the migration**

Define `event_outbox` with `BINARY(16)` event ID, immutable event fields, status enum, attempts, schedule, lease, acknowledgement, error, and optimistic version. Define `saga_instance` with saga fields and version. Add indexes:

```sql
CREATE INDEX idx_outbox_claim
  ON event_outbox(status, next_attempt_at, created_at);
CREATE INDEX idx_outbox_lease
  ON event_outbox(status, lease_expires_at);
CREATE INDEX idx_outbox_reconcile
  ON event_outbox(destination, status, created_at, broker_acknowledged_at);
CREATE INDEX idx_outbox_aggregate
  ON event_outbox(aggregate_type, aggregate_id, created_at);
```

- [ ] **Step 4: Implement the writable transaction component**

Use a dedicated Hikari pool configured with `setReadOnly(false)`. In `inTransaction`, save auto-commit, set it false, commit on success, rollback on any throwable, restore connection state, and rethrow without swallowing the original exception.

```java
@FunctionalInterface
public interface SqlTransaction<T> { T execute(Connection connection) throws Exception; }

public <T> T inTransaction(SqlTransaction<T> work) {
    try (Connection connection = openConnection()) {
        connection.setAutoCommit(false);
        try {
            T value = work.execute(connection);
            connection.commit();
            return value;
        } catch (Throwable failure) {
            connection.rollback();
            throw propagate(failure);
        }
    } catch (SQLException failure) {
        throw new MySqlPoolUnavailableException(failure);
    }
}
```

- [ ] **Step 5: Verify GREEN and commit**

Run: `mvn test -Dtest=TransactionalMySqlTest,OutboxSchemaMySqlIntegrationTest -DexcludedGroups=load`

Expected: all focused tests pass; Docker schema test skips cleanly when Docker is unavailable.

Commit: `git commit -m "feat(outbox): add durable mysql transaction schema"`

---

### Task 2: Outbox Repository, Leasing, and Retry Lifecycle

**Files:**
- Create: `src/main/java/com/recsys/domain/outbox/OutboxDestination.java`
- Create: `src/main/java/com/recsys/domain/outbox/OutboxStatus.java`
- Create: `src/main/java/com/recsys/domain/outbox/OutboxEvent.java`
- Create: `src/main/java/com/recsys/domain/outbox/OutboxRetryPolicy.java`
- Create: `src/main/java/com/recsys/infrastructure/outbox/MySqlOutboxRepository.java`
- Test: `src/test/java/com/recsys/domain/outbox/OutboxRetryPolicyTest.java`
- Test: `src/test/java/com/recsys/infrastructure/outbox/MySqlOutboxRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `enqueue(Connection, OutboxEvent)`, `find(UUID)`, `claimBatch(String, Instant, int, Duration)`, `markDelivered(UUID, long, Instant)`, `reschedule(UUID, long, Instant, String)`, `markDead(UUID, long, String)`, and `scanDelivered(Instant, Instant, int)`.
- Duplicate identical event IDs return the stored event; conflicting immutable content throws `OutboxConflictException`.

- [ ] **Step 1: Write failing retry and repository tests**

```java
@Test void retryDelayIsBoundedAndDeterministicWithInjectedJitter() {
    OutboxRetryPolicy policy = new OutboxRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5), 8, () -> 0.5);
    assertThat(policy.nextAttempt(3, Instant.EPOCH)).isEqualTo(Instant.EPOCH.plusSeconds(4));
    assertThat(policy.isDead(8)).isTrue();
}

@Test void concurrentWorkersNeverClaimTheSameRow() {
    repository.enqueue(event("e1"));
    var first = repository.claimBatch("worker-a", now, 10, Duration.ofSeconds(30));
    var second = repository.claimBatch("worker-b", now, 10, Duration.ofSeconds(30));
    assertThat(first).extracting(OutboxEvent::eventId).containsExactly(uuid("e1"));
    assertThat(second).isEmpty();
}

@Test void expiredLeaseCanBeReclaimed() {
    repository.enqueue(event("e1"));
    repository.claimBatch("worker-a", now, 10, Duration.ofSeconds(30));
    var reclaimed = repository.claimBatch("worker-b", now.plusSeconds(31), 10, Duration.ofSeconds(30));
    assertThat(reclaimed).extracting(OutboxEvent::eventId).containsExactly(uuid("e1"));
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn test -Dtest=OutboxRetryPolicyTest,MySqlOutboxRepositoryIntegrationTest -DexcludedGroups=load`

Expected: missing outbox domain and repository types.

- [ ] **Step 3: Implement immutable domain records and retry policy**

`OutboxEvent` validates non-null event ID, destination, event type, payload, creation time, and a nonblank partition key for Kafka. Backoff is `min(maxDelay, baseDelay * 2^(attempt-1))` with injected multiplicative jitter and no overflow.

- [ ] **Step 4: Implement repository SQL**

Claim inside one transaction using `SELECT ... FOR UPDATE SKIP LOCKED`, followed by version-checked updates to `IN_FLIGHT`. Every terminal mutation requires matching event ID, version, status, and lease owner. Truncate persisted errors to 2,000 characters.

- [ ] **Step 5: Verify GREEN and commit**

Run: `mvn test -Dtest=OutboxRetryPolicyTest,MySqlOutboxRepositoryIntegrationTest -DexcludedGroups=load`

Expected: focused tests pass.

Commit: `git commit -m "feat(outbox): add leased delivery repository"`

---

### Task 3: MySQL Saga State with Atomic Transition Enqueue

**Files:**
- Modify: `src/main/java/com/recsys/application/saga/SagaStateStore.java`
- Modify: `src/main/java/com/recsys/application/saga/SagaOrchestrators.java`
- Create: `src/main/java/com/recsys/infrastructure/saga/MySqlSagaStateStore.java`
- Test: `src/test/java/com/recsys/infrastructure/saga/MySqlSagaStateStoreIntegrationTest.java`
- Modify: `src/test/java/com/recsys/application/saga/SagaOrchestratorTest.java`
- Modify: `src/test/java/com/recsys/application/saga/TccSagaOrchestratorTest.java`

**Interfaces:**
- Produces: `void saveWithEvent(SagaInstance saga, SagaTransitionEvent event)` on `SagaStateStore`.
- In-memory default performs save then optional publisher compatibility; MySQL implementation performs one transaction and does not directly call SQS.

- [ ] **Step 1: Write failing atomicity tests**

```java
@Test void sagaTransitionAndOutboxEventCommitTogether() {
    store.saveWithEvent(saga, transition);
    assertThat(store.find(saga.sagaId())).get().extracting(SagaInstance::status).isEqualTo(STEP_STARTED);
    assertThat(outbox.find(transition.eventId())).isPresent();
}

@Test void outboxInsertFailureRollsBackSagaVersion() {
    outbox.enqueue(conflictingEvent());
    assertThatThrownBy(() -> store.saveWithEvent(saga, conflictingTransition())).isInstanceOf(OutboxConflictException.class);
    assertThat(store.find(saga.sagaId())).get().extracting(SagaInstance::version).isEqualTo(0);
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `mvn test -Dtest=MySqlSagaStateStoreIntegrationTest,SagaOrchestratorTest,TccSagaOrchestratorTest -DexcludedGroups=load`

Expected: `saveWithEvent` is missing.

- [ ] **Step 3: Add the atomic store contract and MySQL implementation**

The MySQL store conditionally inserts/updates `saga_instance` by expected version and enqueues the `SQS_SAGA` outbox event through the same JDBC `Connection`. Zero affected rows raises `SagaConflictException`.

- [ ] **Step 4: Route orchestrator transitions through the new contract**

Create the deterministic `SagaTransitionEvent` first, then call `store.saveWithEvent(saga, event)`. Keep direct `SagaEventPublisher` only for non-durable compatibility stores; prevent production MySQL wiring from double-publishing.

- [ ] **Step 5: Verify GREEN and commit**

Run: `mvn test -Dtest='*Saga*Test' -DexcludedGroups=load`

Expected: all saga tests pass.

Commit: `git commit -m "feat(saga): persist transitions with outbox atomically"`

---

### Task 4: Relay Workers and Reliable Kafka/SQS Adapters

**Files:**
- Create: `src/main/java/com/recsys/application/outbox/OutboxDeliveryAdapter.java`
- Create: `src/main/java/com/recsys/application/outbox/OutboxRelay.java`
- Create: `src/main/java/com/recsys/application/outbox/KafkaOutboxDeliveryAdapter.java`
- Create: `src/main/java/com/recsys/application/outbox/SqsOutboxDeliveryAdapter.java`
- Modify: `src/main/java/com/recsys/infrastructure/messaging/KafkaAsyncEventPublisher.java`
- Test: `src/test/java/com/recsys/application/outbox/OutboxRelayTest.java`
- Test: `src/test/java/com/recsys/application/outbox/KafkaOutboxDeliveryAdapterTest.java`
- Test: `src/test/java/com/recsys/application/outbox/SqsOutboxDeliveryAdapterTest.java`
- Modify: `src/test/java/com/recsys/infrastructure/messaging/KafkaAsyncEventPublisherTest.java`

**Interfaces:**
- Produces: `CompletionStage<DeliveryReceipt> OutboxDeliveryAdapter.deliver(OutboxEvent event)`.
- Produces: `int OutboxRelay.runOnce()`; state advances only after the returned stage completes.

- [ ] **Step 1: Write failing producer configuration and relay tests**

```java
@Test void kafkaPropertiesEnableBoundedIdempotentDelivery() {
    Properties p = KafkaOutboxDeliveryAdapter.producerProps("broker:9092");
    assertThat(p).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
        .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5)
        .containsKeys(ProducerConfig.RETRIES_CONFIG, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                      ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
}

@Test void relayMarksDeliveredOnlyAfterAcknowledgement() {
    CompletableFuture<DeliveryReceipt> acknowledgement = new CompletableFuture<>();
    adapter.deliverReturns(acknowledgement);
    relay.runOnce();
    assertThat(repository.status(eventId)).isEqualTo(IN_FLIGHT);
    acknowledgement.complete(new DeliveryReceipt(Instant.EPOCH.plusSeconds(3)));
    assertThat(repository.status(eventId)).isEqualTo(DELIVERED);
}
@Test void relayReschedulesFailureAndDeadLettersAtCeiling() {
    adapter.deliverFails(new IOException("broker unavailable"));
    relay.runOnce();
    assertThat(repository.status(eventId)).isEqualTo(PENDING);
    repository.setAttemptCount(eventId, retryPolicy.maxAttempts() - 1);
    relay.runOnce();
    assertThat(repository.status(eventId)).isEqualTo(DEAD);
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `mvn test -Dtest=OutboxRelayTest,KafkaOutboxDeliveryAdapterTest,SqsOutboxDeliveryAdapterTest,KafkaAsyncEventPublisherTest`

Expected: relay and adapters are missing; legacy Kafka properties fail reliability assertions.

- [ ] **Step 3: Implement destination adapters**

Kafka sends `ProducerRecord(topic, partitionKey, payload)` and completes only from the callback. SQS includes `eventId`, `aggregateId`, and `eventType` attributes. Neither adapter mutates repository state.

- [ ] **Step 4: Implement relay lifecycle**

Claim a batch, dispatch by destination, then mark delivered with broker time or reschedule/dead-letter through `OutboxRetryPolicy`. Bound concurrent sends and wait only up to the configured relay cycle deadline.

- [ ] **Step 5: Harden the legacy producer too**

Set the same idempotence, `acks=all`, retries, delivery timeout, request timeout, and max-in-flight properties in `KafkaAsyncEventPublisher` so migration traffic is not weaker.

- [ ] **Step 6: Verify GREEN and commit**

Run: `mvn test -Dtest=OutboxRelayTest,KafkaOutboxDeliveryAdapterTest,SqsOutboxDeliveryAdapterTest,KafkaAsyncEventPublisherTest`

Expected: all focused tests pass.

Commit: `git commit -m "feat(outbox): relay events with idempotent delivery"`

---

### Task 5: Durable API Acceptance and Signed Consistency Tokens

**Files:**
- Create: `src/main/java/com/recsys/application/outbox/DurableEventPublisher.java`
- Create: `src/main/java/com/recsys/application/consistency/ConsistencyToken.java`
- Create: `src/main/java/com/recsys/application/consistency/ConsistencyTokenCodec.java`
- Modify: `src/main/java/com/recsys/application/online/OnlineServices.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Test: `src/test/java/com/recsys/application/consistency/ConsistencyTokenCodecTest.java`
- Test: `src/test/java/com/recsys/application/online/OnlineServicesTest.java`

**Interfaces:**
- Produces: `Acceptance DurableEventPublisher.publishOnline(UUID eventId, int userId, String eventType, String payload)`.
- Produces: `String ConsistencyTokenCodec.encode(ConsistencyToken)` and `ConsistencyToken decodeAndVerify(String)`.

- [ ] **Step 1: Write failing token tests**

```java
@Test void roundTripsSignedToken() {
    String encoded = codec.encode(new ConsistencyToken(eventId, 42, issuedAt, issuedAt.plus(Duration.ofHours(24))));
    assertThat(codec.decodeAndVerify(encoded).eventId()).isEqualTo(eventId);
}
@Test void rejectsTampering() { assertThatThrownBy(() -> codec.decodeAndVerify(tampered)).isInstanceOf(InvalidConsistencyTokenException.class); }
@Test void rejectsExpiredToken() { assertThatThrownBy(() -> expiredCodec.decodeAndVerify(token)).isInstanceOf(ExpiredConsistencyTokenException.class); }
```

- [ ] **Step 2: Write failing API acceptance tests**

Assert that a successful feature-view request persists one outbox row and returns `X-Consistency-Token`; repository failure returns `503` without a token; repeated identical event IDs return the same acceptance; conflicting content returns `409`.

- [ ] **Step 3: Run focused tests and verify RED**

Run: `mvn test -Dtest=ConsistencyTokenCodecTest,OnlineServicesTest`

Expected: token and durable publisher types are missing.

- [ ] **Step 4: Implement HMAC token codec**

Use base64url header/payload/signature encoding, `HmacSHA256`, constant-time signature comparison, injected `Clock`, subject user ID, UUID, issued-at, and 24-hour expiry. Reject missing/short secrets at startup.

- [ ] **Step 5: Replace durable production event acceptance**

Move event creation before rendering success, persist synchronously through `DurableEventPublisher`, and attach the token header. Keep constructor overloads for legacy unit tests but wire durable production paths from `OnlinePredictionServer`.

- [ ] **Step 6: Verify GREEN and commit**

Run: `mvn test -Dtest=ConsistencyTokenCodecTest,OnlineServicesTest,AsyncEventPublisherTest`

Expected: focused and compatibility tests pass.

Commit: `git commit -m "feat(api): accept events through durable outbox"`

---

### Task 6: Primary Read-Your-Writes Waiter

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisExecutor.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/RoutingRedisExecutor.java`
- Create: `src/main/java/com/recsys/application/consistency/RedisLineageReader.java`
- Create: `src/main/java/com/recsys/application/consistency/ConsistencyWaiter.java`
- Modify: `src/main/java/com/recsys/application/online/OnlineServices.java`
- Test: `src/test/java/com/recsys/application/consistency/ConsistencyWaiterTest.java`
- Modify: `src/test/java/com/recsys/infrastructure/redis/RoutingRedisExecutorTest.java`
- Modify: `src/test/java/com/recsys/application/online/OnlineServicesTest.java`

**Interfaces:**
- Produces: `<T> T executePrimaryRead(Function<RedisCommands<String,String>,T>)`.
- Produces: `WaitResult await(UUID eventId, int userId, Duration timeout)` returning `APPLIED` or `PENDING`.

- [ ] **Step 1: Write failing routing and waiter tests**

```java
@Test void primaryReadNeverUsesReplica() {
    routing.executePrimaryRead(c -> c.get("lineage"));
    verify(primary).executeRead(any());
    verifyNoInteractions(replica);
}

@Test void waiterStopsWhenLineageAppears() {
    lineage.sequence(false, false, true);
    assertThat(waiter.await(eventId, 42, Duration.ofSeconds(2))).isEqualTo(APPLIED);
}
@Test void waiterReturnsPendingAtDeadline() {
    lineage.alwaysMissing();
    assertThat(waiter.await(eventId, 42, Duration.ofSeconds(2))).isEqualTo(PENDING);
    assertThat(fakeClock.instant()).isEqualTo(Instant.EPOCH.plusSeconds(2));
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn test -Dtest=ConsistencyWaiterTest,RoutingRedisExecutorTest,OnlineServicesTest`

Expected: primary-read and waiter APIs are missing.

- [ ] **Step 3: Add explicit primary-read routing and lineage lookup**

`RoutingRedisExecutor.executePrimaryRead` delegates to `router.writable().executeRead`. `RedisLineageReader` checks `SISMEMBER lineage:event:<eventId> user:<userId>:recent_movies` on the primary and never consults JVM caches.

- [ ] **Step 4: Integrate bounded token handling**

Validate token signature and user binding before recommendation work. Poll with an injected 50 ms sleeper up to two seconds. Return `202` plus `Retry-After: 1` when pending, `400` invalid, `409` expired, and `403` subject mismatch. On `APPLIED`, force the request's feature reads through a primary-read/no-cache path.

- [ ] **Step 5: Verify GREEN and commit**

Run: `mvn test -Dtest=ConsistencyWaiterTest,RoutingRedisExecutorTest,OnlineServicesTest`

Expected: all focused tests pass.

Commit: `git commit -m "feat(consistency): add bounded read-your-writes"`

---

### Task 7: Atomic Top-K Updates and Deterministic Equal-Time Ordering

**Files:**
- Modify: `src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java`
- Modify: `src/test/java/com/recsys/online/flink/OnlineFeatureStreamingJobTest.java`

**Interfaces:**
- Produces one Lua script that atomically updates canonical Top-K, hot-movies, trend feature, metadata, and lineage.
- Produces ordering tuple `(eventTimeMillis, eventId)`; strictly greater tuples win.

- [ ] **Step 1: Write failing deterministic-order tests**

```java
@Test void equalTimestampUsesEventIdTieBreakerRegardlessOfArrivalOrder() {
    apply(snapshot(1000, "b", List.of(movie(2, 9))));
    apply(snapshot(1000, "a", List.of(movie(1, 10))));
    assertThat(readTopK()).containsExactly("2");
}

@Test void replayOfIdenticalVersionIsNoOp() {
    TopKSnapshot value = snapshot(1000, "b", List.of(movie(2, 9)));
    assertThat(apply(value)).isEqualTo(1L);
    assertThat(apply(value)).isZero();
    assertThat(readTopK()).containsExactly("2");
}
@Test void atomicScriptUpdatesAllTopKRepresentations() {
    apply(snapshot(1000, "b", List.of(movie(2, 9))));
    assertThat(redis.zrange(canonicalKey())).containsExactly("2");
    assertThat(redis.zrange(hotMoviesKey())).containsExactly("2");
    assertThat(redis.get(trendKey())).contains("2");
    assertThat(redis.get(versionKey())).isEqualTo("1000|b");
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `mvn test -Dtest=OnlineFeatureStreamingJobTest`

Expected: equal timestamps overwrite by arrival order and related keys are written separately.

- [ ] **Step 3: Add version tuple and single-slot key contract**

Carry a deterministic event/version ID into `TopKSnapshot`. Store version metadata as `eventTimeMillis|eventId`. Compare timestamp numerically and event ID lexicographically in Lua. Use keys such as `topk:{last_hour}:value`, `feature:{last_hour}:hot_movies`, and `feature:{last_hour}:trend` so Redis Cluster accepts the script.

- [ ] **Step 4: Replace separate sinks with one atomic invocation**

The script checks the stored tuple, then deletes/rebuilds both sorted sets, sets the trend string, writes shared metadata, records lineage, and applies TTLs in one call. Remove the duplicate Top-K and trend sink writes without changing upstream operator UIDs unnecessarily; if a sink topology UID must change, document savepoint compatibility in the runbook.

- [ ] **Step 5: Verify GREEN and commit**

Run: `mvn test -Dtest=OnlineFeatureStreamingJobTest`

Expected: all normal Flink tests pass. When Docker is available also run `mvn test -Dgroups=docker -DexcludedGroups=load -Dtest=OnlineFeatureStreamingJobTest`.

Commit: `git commit -m "fix(flink): apply top-k features atomically"`

---

### Task 8: Delivery, Drop, Replica-Lag, Version, and Token Metrics

**Files:**
- Create: `src/main/java/com/recsys/metrics/ConsistencyMetrics.java`
- Create: `src/main/java/com/recsys/infrastructure/redis/RedisReplicaLagProbe.java`
- Modify: `src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisher.java`
- Modify: `src/main/java/com/recsys/application/outbox/OutboxRelay.java`
- Modify: `src/main/java/com/recsys/application/consistency/ConsistencyWaiter.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Test: `src/test/java/com/recsys/metrics/ConsistencyMetricsTest.java`
- Test: `src/test/java/com/recsys/infrastructure/redis/RedisReplicaLagProbeTest.java`

**Interfaces:**
- Produces bounded Micrometer meters named in the specification.
- Produces: `ProbeResult RedisReplicaLagProbe.sample()` with `available` and `lagSeconds`.

- [ ] **Step 1: Write failing meter tests**

```java
@Test void registersRequiredBoundedMeters() {
    relay.recordDelivered(Duration.ofSeconds(3));
    assertThat(registry.get("outbox_delivery_lag_seconds").timer().count()).isEqualTo(1);
    assertThat(registry.get("outbox_delivery_failures_total").counter()).isNotNull();
    assertThat(registry.get("async_events_dropped_total").counter()).isNotNull();
    assertThat(registry.get("redis_replica_lag_seconds").gauge()).isNotNull();
}

@Test void probeFailureReportsUnavailableNotZero() { assertThat(probe.sample().available()).isFalse(); }
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn test -Dtest=ConsistencyMetricsTest,RedisReplicaLagProbeTest`

Expected: consistency metrics and probe are missing.

- [ ] **Step 3: Implement bounded metrics facade**

Register counters/timers/gauges once per registry. Allowed tags are only fixed destination, outcome, and event type enums. Feature-version gauges aggregate minimum, maximum, and age rather than creating per-feature meters.

- [ ] **Step 4: Implement replica lag probe**

Write a monotonic timestamp/version probe to the primary, read via replica routing, and calculate nonnegative lag. Track availability separately. Schedule it from the online server and close its executor during shutdown.

- [ ] **Step 5: Wire metric recording**

Record queue rejection, relay lag/failure/pending state, consistency validation/outcomes/duration, feature-version aggregates, and probe results at the existing `/metrics` endpoint.

- [ ] **Step 6: Verify GREEN and commit**

Run: `mvn test -Dtest=ConsistencyMetricsTest,RedisReplicaLagProbeTest,AsyncEventPublisherTest,OnlineServingMetricsServiceTest`

Expected: all focused tests pass and meter names have no high-cardinality labels.

Commit: `git commit -m "feat(metrics): expose consistency health signals"`

---

### Task 9: Reconciliation Command and Kubernetes CronJob

**Files:**
- Create: `src/main/java/com/recsys/application/reconciliation/OutboxReconciler.java`
- Create: `src/main/java/com/recsys/application/reconciliation/ReconciliationCommand.java`
- Create: `k8s/base/outbox-reconciliation-cronjob.yaml`
- Modify: `k8s/base/kustomization.yaml`
- Modify: `k8s/base/configmap.yaml`
- Test: `src/test/java/com/recsys/application/reconciliation/OutboxReconcilerTest.java`
- Test: `src/test/java/com/recsys/application/reconciliation/ReconciliationCommandTest.java`

**Interfaces:**
- Produces: `ReconciliationResult reconcile(Instant from, Instant to, int maxBatch, boolean repair)`.
- Consumes delivered `KAFKA_ONLINE` rows, primary Redis lineage, outbox leases, Kafka delivery adapter, and consistency metrics.

- [ ] **Step 1: Write failing reconciliation tests**

```java
@Test void republishesDeliveredEventMissingFromLineage() {
    repository.scanReturns(deliveredEvent);
    lineage.missing(deliveredEvent.eventId());
    assertThat(reconciler.reconcile(from, to, 100, true).republished()).isEqualTo(1);
    verify(adapter).deliver(deliveredEvent);
}

@Test void skipsPresentAndDeadEvents() {
    repository.scanReturns(presentEvent, deadEvent);
    lineage.present(presentEvent.eventId());
    assertThat(reconciler.reconcile(from, to, 100, true).republished()).isZero();
    verifyNoInteractions(adapter);
}
@Test void reportOnlyCountsMissingWithoutRepublishing() {
    repository.scanReturns(deliveredEvent);
    lineage.missing(deliveredEvent.eventId());
    assertThat(reconciler.reconcile(from, to, 100, false).missing()).isEqualTo(1);
    verifyNoInteractions(adapter);
}
@Test void overlappingLeasePreventsDuplicateRepair() {
    repository.reconciliationLeaseHeldBy("worker-a", deliveredEvent.eventId());
    assertThat(workerB.reconcile(from, to, 100, true).republished()).isZero();
}
@Test void defaultsToPreviousTwentyFourHours() {
    command.run(new String[0]);
    verify(reconciler).reconcile(now.minus(Duration.ofHours(24)), now, defaultBatch, false);
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `mvn test -Dtest=OutboxReconcilerTest,ReconciliationCommandTest`

Expected: reconciliation types are missing.

- [ ] **Step 3: Implement bounded reconciliation**

Validate `from < to`, cap batch size, scan only delivered Kafka events, claim a reconciliation lease, check primary lineage, and republish the original ID/key/payload when repair is enabled. A later run counts `repaired` only after lineage appears. Never auto-repair `DEAD` rows.

- [ ] **Step 4: Add command entry point and manifest**

Support environment variables `RECONCILIATION_WINDOW_HOURS=24`, `RECONCILIATION_MAX_BATCH`, `RECONCILIATION_REPAIR=false`, and lease duration. The CronJob uses `concurrencyPolicy: Forbid`, a bounded deadline, non-root security context, existing ConfigMap/Secret inputs, and starts in report-only mode.

- [ ] **Step 5: Verify GREEN and commit**

Run: `mvn test -Dtest=OutboxReconcilerTest,ReconciliationCommandTest`

Run: `kubectl kustomize k8s/base >/tmp/recsys-kustomize.yaml`

Expected: tests pass and Kustomize renders one valid reconciliation CronJob.

Commit: `git commit -m "feat(consistency): reconcile missing redis lineage"`

---

### Task 10: Production Wiring, Migration Runbook, and End-to-End Gate

**Files:**
- Create: `src/main/java/com/recsys/application/outbox/OutboxRelayCommand.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Modify: `src/main/resources/application.yml`
- Modify: `k8s/base/configmap.yaml`
- Create: `k8s/base/outbox-relay-deployment.yaml`
- Modify: `k8s/base/kustomization.yaml`
- Create: `docs/runbooks/durable-eventual-consistency.md`
- Create: `src/test/java/com/recsys/integration/DurableEventualConsistencyIntegrationTest.java`

**Interfaces:**
- Wires MySQL outbox, saga store, relay adapters, token codec, waiter, metrics, and shutdown lifecycle.
- Provides Docker-tagged MySQL → relay → Kafka → Flink → Redis → token-read proof.

- [ ] **Step 1: Write the failing wiring and end-to-end tests**

Add a non-Docker configuration test asserting startup rejects a missing/short token secret when durable mode is enabled and retains legacy local mode when disabled. Add a `@Tag("docker")` Testcontainers test that persists one event, runs a relay cycle, consumes/applies it, confirms Redis lineage, and completes a token wait.

- [ ] **Step 2: Run configuration test and verify RED**

Run: `mvn test -Dtest=DurableConsistencyConfigurationTest`

Expected: production wiring/configuration type is missing.

- [ ] **Step 3: Implement lifecycle wiring and deployment**

Create explicit environment-gated durable configuration. Start relay workers as their own deployment, not inside every online-serving replica. Register shutdown hooks for pools, producers, SQS clients, schedulers, and Redis connections. Add readiness checks for MySQL and relay backlog age.

- [ ] **Step 4: Write the migration and rollback runbook**

Document Flyway migration, report-only rollout, durable API enablement, saga cutover, atomic Redis script/savepoint implications, token enablement, reconciliation repair enablement, alerts, dead-letter operations, and rollback that preserves pending rows.

- [ ] **Step 5: Run focused, full, and packaging verification**

Run: `mvn test`

Expected: all default tests pass.

Run: `mvn package -DskipTests`

Expected: `BUILD SUCCESS`.

When Docker is available run:

`mvn test -Dgroups=docker -DexcludedGroups=load -Dtest=OutboxSchemaMySqlIntegrationTest,MySqlOutboxRepositoryIntegrationTest,MySqlSagaStateStoreIntegrationTest,OnlineFeatureStreamingJobTest,DurableEventualConsistencyIntegrationTest`

Expected: all Docker integration tests pass.

- [ ] **Step 6: Commit final wiring**

Commit: `git commit -m "feat(consistency): deploy durable event pipeline"`

---

## Final Review Checklist

- [ ] Every accepted API event is committed before an acceptance token is returned.
- [ ] Saga state and transition event share one JDBC transaction.
- [ ] Relay acknowledgement, lease recovery, retry, and dead-letter behavior are tested.
- [ ] Kafka uses idempotence, `acks=all`, explicit retries, and explicit timeouts.
- [ ] Token reads use primary Redis and bypass local feature caches.
- [ ] Two-second pending reads return `202` and `Retry-After: 1`.
- [ ] Top-K representations update atomically and equal timestamps use event-ID tie-breaking.
- [ ] All requested metrics are scrapeable without high-cardinality labels.
- [ ] Reconciliation defaults to 24 hours, respects batch bounds, and safely republishes missing events.
- [ ] Existing unrelated working-tree files remain unstaged and unchanged.
