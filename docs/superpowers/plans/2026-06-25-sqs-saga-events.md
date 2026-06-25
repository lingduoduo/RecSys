# SQS Saga Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish saga transition events to SQS through a dedicated `SagaEventPublisher` implementation.

**Architecture:** `SqsSagaEventPublisher` serializes `SagaTransitionEvent` to JSON and sends it with routing message attributes. `SagaEventPublishers` creates SQS or `NOOP` publishers from environment variables.

**Tech Stack:** Java 17, AWS SDK v2 SQS, Jackson, JUnit 5, AssertJ, Mockito.

## Global Constraints

- Existing saga tests using `SagaEventPublisher.NOOP` must pass unchanged.
- SQS disabled by default must require no AWS credentials.
- Standard SQS queues only; FIFO and participant command queues are out of scope.

---

### Task 1: SQS Saga Publisher

**Files:**
- Create: `src/main/java/com/recsys/application/saga/SqsSagaEventPublisher.java`
- Create: `src/test/java/com/recsys/application/saga/SqsSagaEventPublisherTest.java`

**Interfaces:**
- Consumes: `SagaTransitionEvent`
- Produces: `public final class SqsSagaEventPublisher implements SagaEventPublisher`

- [ ] **Step 1: Write failing publisher tests**

Verify queue URL, JSON body, routing attributes, strict-mode failure throwing, best-effort failure swallowing, and null rejection.

- [ ] **Step 2: Verify RED**

Run: `mvn test -Dtest=SqsSagaEventPublisherTest`

Expected: compilation failure because `SqsSagaEventPublisher` does not exist.

- [ ] **Step 3: Implement publisher**

Serialize with Jackson, build `SendMessageRequest`, attach string message attributes, throw `SagaException` in strict mode, log in best-effort mode.

- [ ] **Step 4: Verify GREEN**

Run: `mvn test -Dtest=SqsSagaEventPublisherTest`

Expected: pass.

---

### Task 2: Saga Publisher Factory

**Files:**
- Create: `src/main/java/com/recsys/application/saga/SagaEventPublishers.java`
- Create: `src/test/java/com/recsys/application/saga/SagaEventPublishersTest.java`

**Interfaces:**
- Produces: `public static SagaEventPublisher fromEnvironment()`
- Produces: package-private `static SagaEventPublisher from(Map<String,String> env, Function<Region,SqsClient> sqsClientFactory)`

- [ ] **Step 1: Write failing factory tests**

Cover disabled -> `NOOP`, enabled+queue -> `SqsSagaEventPublisher`, enabled+blank -> `NOOP`.

- [ ] **Step 2: Verify RED**

Run: `mvn test -Dtest=SagaEventPublishersTest`

Expected: compilation failure because the factory does not exist.

- [ ] **Step 3: Implement factory**

Read `SAGA_EVENTS_SQS_*` and `AWS_REGION`, then build the strict or best-effort publisher.

- [ ] **Step 4: Verify GREEN**

Run: `mvn test -Dtest=SagaEventPublishersTest,SqsSagaEventPublisherTest`

Expected: pass.

---

### Task 3: Saga Contract Test And Config Docs

**Files:**
- Create: `src/test/java/com/recsys/application/saga/SagaOrchestratorSqsPublisherTest.java`
- Modify: `k8s/base/configmap.yaml`

**Interfaces:**
- Consumes: existing `SagaOrchestrators.Standard`

- [ ] **Step 1: Add ordering contract test**

Use a fake `SagaStateStore` and fake publisher to verify transition state is saved before event publication.

- [ ] **Step 2: Add disabled env docs**

Add disabled-by-default `SAGA_EVENTS_SQS_ENABLED`, blank `SAGA_EVENTS_SQS_QUEUE_URL`, and `SAGA_EVENTS_SQS_BEST_EFFORT=false` to config map.

- [ ] **Step 3: Verify saga tests**

Run: `mvn test -Dtest=SqsSagaEventPublisherTest,SagaEventPublishersTest,SagaOrchestratorSqsPublisherTest,SagaOrchestratorTest,TccSagaOrchestratorTest`

Expected: pass.

