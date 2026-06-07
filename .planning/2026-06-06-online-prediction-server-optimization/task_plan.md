# Task Plan: Optimize Online Prediction Server (Port 7010)

## Goal
Produce a repo-grounded implementation plan that makes port 7010 predictable under Redis latency and traffic spikes, exposes actionable operations metrics, and validates the result against explicit latency and overload SLOs.

## Current Phase
Complete

## Phases

### Phase 1: Requirements & Discovery
- [x] Understand user intent
- [x] Identify existing port 7010 serving, Redis, load-shedding, and metrics behavior
- [x] Document findings
- **Status:** complete

### Phase 2: Planning & Structure
- [x] Define optimization priorities and target SLOs
- [x] Map work to concrete source, test, documentation, and Kubernetes files
- **Status:** complete

### Phase 3: Plan Authoring
- [x] Write an implementation-ready plan
- [x] Include rollout order, tests, and acceptance criteria
- **Status:** complete

### Phase 4: Testing & Verification
- [x] Cross-check plan against current implementation and existing tests
- [x] Verify the plan does not duplicate existing Redis HA work
- **Status:** complete

### Phase 5: Delivery
- [x] Review outputs
- [x] Deliver plan to user
- **Status:** complete

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Fix admission control before deeper recommendation optimizations | The current shedder runs inside the blocking executor, allowing executor queue growth before rejection. |
| Keep Redis HA out of scope | Sentinel/ElastiCache connection work already exists; this plan focuses on request-path resilience and observability. |
| Preserve `/online/ops` and add standards-based metrics | Existing JSON snapshots are useful for humans but insufficient for alerting/HPA. |
| Split liveness and readiness | The shared `/health` can cause Kubernetes liveness restarts merely because the pod is intentionally draining. |
| Add stale-if-error behavior for online features and top-K | Real-time recommendations can degrade gracefully instead of returning 500 during brief Redis failures. |
| Make load-test measurements the tuning authority | Defaults such as concurrency 512 and Redis pool max 50 are not demonstrably aligned. |

## Errors Encountered
| Error | Resolution |
|-------|------------|
| Initial broad search referenced missing root `docker-compose.yml` | Used `streaming/online-serving/docker-compose.yml` and repository-specific deployment files instead. |
