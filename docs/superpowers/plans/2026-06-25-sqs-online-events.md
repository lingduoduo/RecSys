# SQS Online Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire port 7010 online side-channel events through the shared SQS-capable publisher factory.

**Architecture:** Add `AsyncEventPublisherFactory` for non-Spring services, reading prefixed environment variables and returning SQS, Kafka, or log-only publishers. `OnlinePredictionServer` uses the factory instead of direct base publisher construction.

**Tech Stack:** Java 17, Armeria main class, AWS SDK v2 SQS, Kafka client, Kubernetes manifests, JUnit 5.

## Global Constraints

- Online recommendation and feature read paths stay synchronous and low latency.
- Env vars absent means log-only publisher, matching current behavior.
- SQS transport depends on `2026-06-25-sqs-async-event-transport.md`.

---

### Task 1: Publisher Factory

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisherFactory.java`
- Create: `src/test/java/com/recsys/infrastructure/messaging/AsyncEventPublisherFactoryTest.java`

**Interfaces:**
- Produces: `public static AsyncEventPublisher fromEnvironment(String envPrefix)`
- Produces: package-private `static AsyncEventPublisher from(String envPrefix, Map<String,String> env, Function<Region,SqsClient> sqsClientFactory)`

- [ ] **Step 1: Write failing factory tests**

Cover default log-only, SQS enabled with queue URL, SQS blank falling through, and Kafka enabled with bootstrap.

- [ ] **Step 2: Verify RED**

Run: `mvn test -Dtest=AsyncEventPublisherFactoryTest`

Expected: compilation failure because the factory does not exist.

- [ ] **Step 3: Implement factory**

Read uppercase-prefixed env vars, parse booleans conservatively, build `SqsAsyncEventPublisher`, `KafkaAsyncEventPublisher`, or base `AsyncEventPublisher`.

- [ ] **Step 4: Verify GREEN**

Run: `mvn test -Dtest=AsyncEventPublisherFactoryTest`

Expected: pass.

---

### Task 2: Online Server Wiring

**Files:**
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Modify: `k8s/base/online-serving.yaml`
- Test: `src/test/java/com/recsys/api/online/OnlinePredictionServerIntegrationTest.java`

**Interfaces:**
- Consumes: `AsyncEventPublisherFactory.fromEnvironment("ONLINE_EVENTS")`

- [ ] **Step 1: Add testable publisher creation method**

Extract package-private static `createAsyncEventPublisher()` in `OnlinePredictionServer`.

- [ ] **Step 2: Update server wiring**

Use `createAsyncEventPublisher()` where the server currently constructs `new AsyncEventPublisher()`.

- [ ] **Step 3: Add Kubernetes defaults**

Add `ONLINE_EVENTS_SQS_ENABLED=false` and blank `ONLINE_EVENTS_SQS_QUEUE_URL` to the online-serving deployment env.

- [ ] **Step 4: Verify focused tests**

Run: `mvn test -Dtest=AsyncEventPublisherFactoryTest,OnlinePredictionServerIntegrationTest`

Expected: pass without AWS config.

---

### Task 3: Verify

- [ ] **Step 1: Run messaging and online tests**

Run: `mvn test -Dtest=SqsAsyncEventPublisherTest,AsyncEventPublisherFactoryTest,OnlinePredictionServerIntegrationTest,OnlineOpsServiceTest`

Expected: pass.

