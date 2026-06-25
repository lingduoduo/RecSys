# SQS Async Event Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an AWS SQS transport for the existing `AsyncEventPublisher` abstraction and config-gate it ahead of Kafka for A/B exposure events.

**Architecture:** `SqsAsyncEventPublisher` extends `AsyncEventPublisher`, preserves the bounded local queue semantics, and drains message bodies to SQS in `SendMessageBatch` calls of at most 10 entries. `ModelEventConfig` chooses SQS, Kafka, or log-only based on configuration, with log-only remaining the default.

**Tech Stack:** Java 17, Maven, AWS SDK v2 SQS, Spring `@Configuration`, JUnit 5, AssertJ, Mockito.

## Global Constraints

- Default behavior must not require AWS credentials, an SQS queue, Kafka, or network access.
- `publish(String)` must remain non-blocking and must never fail user-facing requests.
- SQS is standard-queue-only for this slice; FIFO fields and per-event routing are out of scope.
- Use explicit file staging and preserve unrelated worktree changes.

---

### Task 1: SQS Publisher Tests And Dependency

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/com/recsys/infrastructure/messaging/SqsAsyncEventPublisherTest.java`

**Interfaces:**
- Consumes: `AsyncEventPublisher.sendBatch(List<String>)`
- Produces: expected constructor `SqsAsyncEventPublisher(SqsClient, String, int, int)`

- [ ] **Step 1: Add AWS SDK SQS dependency**

Add property `aws.sdk.version` and dependency `software.amazon.awssdk:sqs`.

- [ ] **Step 2: Write failing publisher tests**

Test batching, request body preservation, partial failure swallowing, and thrown send swallowing with a Mockito `SqsClient`.

- [ ] **Step 3: Run test to verify RED**

Run: `mvn test -Dtest=SqsAsyncEventPublisherTest`

Expected: compilation failure because `SqsAsyncEventPublisher` does not exist.

---

### Task 2: SQS Publisher Implementation

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/messaging/SqsAsyncEventPublisher.java`
- Test: `src/test/java/com/recsys/infrastructure/messaging/SqsAsyncEventPublisherTest.java`

**Interfaces:**
- Consumes: AWS SDK `SqsClient.sendMessageBatch(SendMessageBatchRequest)`
- Produces: `public class SqsAsyncEventPublisher extends AsyncEventPublisher`

- [ ] **Step 1: Implement `SqsAsyncEventPublisher`**

Implement constructor validation, batch chunking at 10 entries, `super.sendBatch(events)`, request exception handling, partial failure logging, and `close()` delegating to `super.close()`.

- [ ] **Step 2: Run focused tests**

Run: `mvn test -Dtest=SqsAsyncEventPublisherTest`

Expected: pass.

---

### Task 3: Model Event Config Gating

**Files:**
- Modify: `src/main/java/com/recsys/config/ModelEventConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/recsys/config/ModelEventConfigTest.java`

**Interfaces:**
- Consumes: `SqsAsyncEventPublisher(SqsClient, String)`
- Produces: `abExposurePublisher(boolean sqsEnabled, String sqsQueueUrl, String awsRegion, boolean kafkaEnabled, String bootstrapServers, String topic)`

- [ ] **Step 1: Write failing config tests**

Add tests showing SQS wins when enabled with queue URL, blank SQS falls through to Kafka, and disabled transports return base `AsyncEventPublisher`.

- [ ] **Step 2: Run config tests to verify RED**

Run: `mvn test -Dtest=ModelEventConfigTest`

Expected: compilation failure because the new method signature does not exist.

- [ ] **Step 3: Implement config gating**

Update the Spring bean to read `recsys.events.sqs.*` and `recsys.events.kafka.*`, create an SQS client when SQS is selected, otherwise fall back to Kafka/log-only.

- [ ] **Step 4: Document application properties**

Add `recsys.events.sqs.enabled`, `queue-url`, and `region` defaults to `application.yml`.

- [ ] **Step 5: Run focused tests**

Run: `mvn test -Dtest=SqsAsyncEventPublisherTest,ModelEventConfigTest,KafkaAsyncEventPublisherTest`

Expected: pass.

---

### Task 4: Verify

**Files:**
- All files above

- [ ] **Step 1: Run full tests**

Run: `mvn test`

Expected: pass with SQS disabled by default and no AWS credentials.

