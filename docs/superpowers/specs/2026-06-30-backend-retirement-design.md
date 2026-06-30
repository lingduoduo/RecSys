# Backend Retirement (Full Decommission) — Design

**Date:** 2026-06-30
**Status:** Approved (design)
**Topic slug:** `backend-retirement`
**Paired plan:** `docs/superpowers/plans/2026-06-30-backend-retirement.md` (to be written)

## Goal

Permanently decommission the entire Recsys backend — all four services and their
shared infrastructure — in a single, ordered, verifiable procedure. The wind-down
must be **graceful**: the last in-flight requests drain cleanly (no dropped
requests, callers get proper 503/Retry-After), then resources tear down with no
orphans.

This is a one-way, irreversible operation. All runtime state is treated as
disposable — there is **no export/backup step**.

## Scope & assumptions

- **In scope:** the four services in this repo and their k8s/cloud resources —
  API Gateway (8010), Model Serving (8080), Online Serving (7010), RecSys Serving
  (6010) — plus shared infra they own (Redis; ALB target groups / Cloud Map
  registrations / IRSA). A new orchestration script, one small Java correctness
  fix, and an operational runbook.
- **Out of scope:** the Flink streaming **jobs**, which live in the separate
  `Recsys-Streaming-Pipeline` repo. This repo holds only the Kafka *publishers*
  (which already flush on `close()`). Stopping the Flink jobs and their brokers is
  a **runbook step that points at that repo**, not code we change here.
- **State preservation:** none. Redis embeddings (`i2vEmb:`/`u2vEmb:`), topk
  trending stores, and online-learner params are all regenerable from the
  offline/streaming pipelines. MySQL data, if any, is also considered disposable
  for this retirement. Confirmed with the requester.
- **Approach:** "Orchestration-first" — automate the two genuinely risky parts
  (cross-service ordering + drain verification) and fix the one real correctness
  wrinkle; do **not** build a parallel cluster-wide drain-control system, since
  k8s pod-termination already provides graceful per-pod draining.

## Background — what already exists

The system already has strong **per-pod** graceful shutdown:

- Readiness flips to 503 on SIGTERM. Spring path: `GracefulShutdownSupport`
  (`ContextClosedEvent`, `HIGHEST_PRECEDENCE`) → `LoadShedder.markShuttingDown()`.
  Armeria path: `OnlineHealthService` returns 503 when `loadShedder.shouldDrain()`.
- `server.shutdown: graceful` + `timeout-per-shutdown-phase: 30s` (Spring Boot);
  Armeria `gracefulShutdownTimeoutMillis(1_000, 30_000)` (Online).
- JVM shutdown hooks / `@PreDestroy` flush Kafka publishers, do a final
  online-learner flush, close ONNX runtimes, and close Redis pools.
- k8s: `preStop: ["sleep","5"]` + `terminationGracePeriodSeconds: 60` on all
  services; readiness/liveness on `/health/ready` and `/health/live`.

**The gap for a whole-system retirement** is cross-service ordering. A plain
`kubectl delete` SIGTERMs the gateway and backends in parallel, so the gateway can
still proxy a request to a backend that is already mid-teardown. We need an
orchestrated sequence that drains the gateway *fully* before any backend is
touched, with verification at each step.

Drain progress is observable today via:
- `GET /health/ready` (503 while draining) on every service.
- Metric `recsys.load_shedder.in_flight_requests` (gauge) on the Spring path;
  Online exposes in-flight via `OnlineHealthService` / `OnlineLoadShedder`.

## Retirement sequence

The script executes phases strictly in order. Each phase **verifies** before the
next begins — no blind sleeps. The core invariant: **the gateway is fully drained
before any backend is touched**, so the gateway can never proxy to a backend that
is mid-teardown. The three backends never call each other (only the gateway calls
them — confirmed), so they may drain in parallel with one another.

| Phase | Name | Action | Verify gate |
|---|---|---|---|
| 0 | Pre-flight | Confirm context/namespace; snapshot replica counts; confirm "all state disposable"; require typed point-of-no-return confirmation. | Context+namespace match expectations. |
| 1 | Gateway drain | Scale gateway Deployment to 0. k8s deregisters it from ALB/endpoints; preStop sleep 5 + SIGTERM flips readiness 503 and drains in-flight proxied requests. | Gateway pods gone **and** no in-flight (readiness/metrics). After this, no new external traffic can reach any backend. |
| 2 | Backend drain | Scale the 3 backends (model/online/recsys) to 0, in parallel. Each flips readiness 503, drains in-flight within its 30s budget, runs `@PreDestroy`/shutdown hooks (flush Kafka publishers, final learner flush, close ONNX, close Redis pools). | All backend pods Terminated; in-flight gauges at 0. |
| 3 | Infra teardown | Now that nothing reads/writes Redis, tear down Redis. (Flink jobs in the separate repo must be stopped before their Kafka brokers — runbook pointer.) | Redis resources gone. |
| 4 | Manifest delete | `kubectl delete -k` the kustomization(s); remove Cloud Map registrations, ALB target groups, IRSA, etc. | Manifests deleted. |
| 5 | Verify clean | Assert zero pods, zero PVCs/services left, no orphaned cloud resources. Print teardown report. | Clean-state assertions pass. |

`--keep-infra` stops before Phase 3 (leaves Redis/cloud infra in place — useful
for staged retirement or when infra is shared).

## Components

### 1. Java: graceful executor drain (correctness fix)

Three sites force-terminate recall executors with `shutdownNow()`, interrupting
in-flight recall work during shutdown:

- `application/model/ModelRuntimeProvider.java:234`
- `api/online/OnlinePredictionServer.java:166`
- `api/online/OnlinePredictionServer.java:176`

(`api/serving/RecSysServer.java:152` already uses a bare graceful
`executor.shutdown()` but with no bounded await.)

Add one shared helper and apply it at all four sites so every backend drains
identically:

```java
// com.recsys.loadshed.Executors  (new)
static void shutdownGracefully(ExecutorService ex, Duration timeout) {
    ex.shutdown();                                   // stop accepting; let running finish
    try {
        if (!ex.awaitTermination(timeout.toMillis(), MILLISECONDS))
            ex.shutdownNow();                         // deadline hit → force
    } catch (InterruptedException e) {
        ex.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```

Default timeout ~5s, env-overridable, comfortably inside the 30s pod budget.

**No new endpoints and no drain-mode flag.** Draining is triggered by scaling to 0
(which causes SIGTERM and the existing readiness flip); drain progress is read from
the existing `/health/ready` + `recsys.load_shedder.in_flight_requests`. Nothing
new in the request path.

### 2. Orchestration script — `scripts/retire-backend.sh`

```
retire-backend.sh [--namespace NS] [--context CTX] [--dry-run] [--yes]
                  [--drain-timeout 60] [--keep-infra]
```

- Strict mode (`set -euo pipefail`); each phase is a function.
- `--dry-run` prints the kubectl/aws commands without running them.
- Pre-flight verifies context+namespace, prints a replica snapshot, and requires
  an explicit typed confirmation (skippable with `--yes` for automation).
- `verify_drained()` polls `kubectl get pods` + each pod's `/health/ready` (or the
  in-flight gauge) until pods are gone / in-flight == 0, bounded by
  `--drain-timeout`. **On timeout it aborts with a clear message** — no silent
  force-kill, because graceful drain is the requirement.
- Phases 1→5 exactly as the table above; `--keep-infra` stops before Phase 3.
- **Idempotent:** re-running after a partial failure resumes cleanly (scaling an
  already-0 deployment is a no-op; deleting an absent resource is tolerated).
- Ends with a teardown report (what was deleted, what remains).
- Reuses repo conventions (sits alongside `run-microservices-local.sh` /
  `run-with-jvm-tuning.sh`); only external deps are `kubectl` and, for Phase 4,
  `aws`.

### 3. Runbook — `docs/runbooks/retire-backend.md`

Operational artifact (kept out of `docs/superpowers/`, which is for
specs/plans/designs). Contents: prerequisites, the ordered procedure mapping each
step to its script phase, the separate-repo Flink/Kafka shutdown pointer, a
verification checklist, and an explicit "point of no return / irreversible / all
state discarded" callout.

## Testing strategy

- **Java (TDD):** unit-test `shutdownGracefully` — (a) an in-flight task completes
  within the deadline, (b) a task past the deadline is force-cancelled, (c)
  interruption is handled and the flag re-set. This is the only new Java logic.
- **Script:** a `--dry-run` smoke test asserting the emitted command sequence
  matches the required order (gateway before backends before infra). Optionally a
  kind/minikube end-to-end check tagged `docker` (per the repo convention that
  docker-tagged tests run locally only via `-Dgroups=docker`).
- The script's **verify gates** are the runtime test — they assert
  drained-before-proceed at execution time.

## Risks & mitigations

- **Drain hangs past budget** → `verify_drained()` is bounded by
  `--drain-timeout` and aborts loudly rather than force-killing; operator decides.
- **Irreversibility** → typed pre-flight confirmation + runbook point-of-no-return
  callout; `--keep-infra` allows a staged dry-out of the app tier first.
- **Orphaned cloud resources** (ALB TGs, Cloud Map, IRSA) → Phase 5 explicitly
  asserts a clean state and reports anything left behind.
- **Streaming coupling** → Flink job teardown is a documented runbook step against
  the separate repo; the Kafka publishers here already flush on `close()`.

## Out of scope (YAGNI)

- Cluster-wide drain-mode endpoint / shared `system:draining` flag (Approach B) —
  duplicates k8s per-pod termination.
- Any state export/backup — all state is disposable.
- Changes to the Flink streaming jobs (separate repo).
