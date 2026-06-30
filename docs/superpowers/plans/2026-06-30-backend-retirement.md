# Backend Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a graceful-executor-drain correctness fix, an ordered whole-system decommission script, and an operational runbook so the entire backend can be retired gracefully and verifiably.

**Architecture:** Orchestration-first. A new shared `GracefulExecutors.shutdownGracefully` helper replaces three `shutdownNow()` call sites so in-flight recall work drains within a bounded deadline. A `scripts/retire-backend.sh` orchestrator drives the ordered teardown (gateway → backends → infra → manifests) with drain-verification gates, leveraging the existing readiness/`recsys.load_shedder.in_flight_requests` machinery — no new request-path code. A runbook documents the operator procedure.

**Tech Stack:** Java 17, JUnit 5 + AssertJ, Maven (Surefire), Armeria/Spring Boot, Bash + kubectl/aws.

## Global Constraints

- Java package root: `com.recsys`; tests mirror under `src/test/java/com/recsys/...`.
- Test framework: JUnit 5 (`org.junit.jupiter.api.Test`) + AssertJ (`assertThat`).
- Helper class name is `GracefulExecutors` (NOT `Executors` — avoids collision with `java.util.concurrent.Executors`).
- Executor drain default timeout: 5000 ms, overridable via env var `RECSYS_EXECUTOR_SHUTDOWN_TIMEOUT_MS`; must stay well inside the 30s pod shutdown budget.
- All retirement state is disposable — no export/backup logic anywhere.
- Scripts live in `scripts/`, plain `.sh`, `set -euo pipefail`, match existing style (`run-microservices-local.sh`).
- Commit after each task. Never commit to `main`; work stays on branch `feat/backend-retirement`.

---

### Task 1: `GracefulExecutors` helper

**Files:**
- Create: `src/main/java/com/recsys/loadshed/GracefulExecutors.java`
- Test: `src/test/java/com/recsys/loadshed/GracefulExecutorsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `static void GracefulExecutors.shutdownGracefully(ExecutorService ex, Duration timeout)` — calls `shutdown()`, waits up to `timeout` for running tasks, force-`shutdownNow()` on timeout or interruption (re-setting the interrupt flag). Null-safe: returns immediately if `ex == null`.
  - `static void GracefulExecutors.shutdownGracefully(ExecutorService ex)` — convenience overload using `defaultTimeout()`.
  - `static Duration GracefulExecutors.defaultTimeout()` — reads `RECSYS_EXECUTOR_SHUTDOWN_TIMEOUT_MS` (default 5000ms).

- [ ] **Step 1: Write the failing tests**

```java
// src/test/java/com/recsys/loadshed/GracefulExecutorsTest.java
package com.recsys.loadshed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulExecutorsTest {

    @Test
    void shutdownGracefully_letsInFlightTaskComplete() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        AtomicBoolean finished = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);

        ex.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(100);
                finished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        started.await(1, TimeUnit.SECONDS);

        GracefulExecutors.shutdownGracefully(ex, Duration.ofSeconds(2));

        assertThat(ex.isTerminated()).isTrue();
        assertThat(finished).isTrue();
    }

    @Test
    void shutdownGracefully_forceCancelsPastDeadline() {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);

        ex.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            started.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long before = System.nanoTime();
        GracefulExecutors.shutdownGracefully(ex, Duration.ofMillis(200));
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertThat(ex.isShutdown()).isTrue();
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void shutdownGracefully_isNullSafe() {
        GracefulExecutors.shutdownGracefully(null, Duration.ofMillis(10));
        // no exception thrown
    }

    @Test
    void defaultTimeout_isFiveSecondsByDefault() {
        // RECSYS_EXECUTOR_SHUTDOWN_TIMEOUT_MS unset in test env
        assertThat(GracefulExecutors.defaultTimeout()).isEqualTo(Duration.ofMillis(5000));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=GracefulExecutorsTest`
Expected: FAIL — `GracefulExecutors` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
// src/main/java/com/recsys/loadshed/GracefulExecutors.java
package com.recsys.loadshed;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shuts executors down gracefully: stop accepting new work, let running tasks
 * finish within a bounded deadline, then force-terminate. Used on the service
 * shutdown paths so in-flight recall work drains instead of being interrupted.
 */
public final class GracefulExecutors {

    private static final long DEFAULT_TIMEOUT_MS = 5000L;
    private static final String TIMEOUT_ENV = "RECSYS_EXECUTOR_SHUTDOWN_TIMEOUT_MS";

    private GracefulExecutors() {
    }

    public static Duration defaultTimeout() {
        String raw = System.getenv(TIMEOUT_ENV);
        if (raw == null || raw.isBlank()) {
            return Duration.ofMillis(DEFAULT_TIMEOUT_MS);
        }
        try {
            return Duration.ofMillis(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return Duration.ofMillis(DEFAULT_TIMEOUT_MS);
        }
    }

    public static void shutdownGracefully(ExecutorService ex) {
        shutdownGracefully(ex, defaultTimeout());
    }

    public static void shutdownGracefully(ExecutorService ex, Duration timeout) {
        if (ex == null) {
            return;
        }
        ex.shutdown();
        try {
            if (!ex.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=GracefulExecutorsTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/loadshed/GracefulExecutors.java \
        src/test/java/com/recsys/loadshed/GracefulExecutorsTest.java
git commit -m "feat(loadshed): add GracefulExecutors.shutdownGracefully helper"
```

---

### Task 2: Apply graceful drain at the shutdown sites

**Files:**
- Modify: `src/main/java/com/recsys/application/model/ModelRuntimeProvider.java:234`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java:166` and `:176`
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java:152`

**Interfaces:**
- Consumes: `GracefulExecutors.shutdownGracefully(ExecutorService)` from Task 1.
- Produces: nothing new (behavioral change only).

> No unit test drives this task directly (it edits JVM shutdown hooks / `@PreDestroy` that are not exercised by the existing suite). The deliverable's gate is a green full build + full test run, confirming the wiring compiles and nothing regresses. The behavior itself is covered by Task 1's unit tests of the helper.

- [ ] **Step 1: Edit `ModelRuntimeProvider.close()`**

Replace lines 233-236:

```java
        if (recallExecutor != null) {
            recallExecutor.shutdownNow();
            recallExecutor = null;
        }
```

with:

```java
        if (recallExecutor != null) {
            com.recsys.loadshed.GracefulExecutors.shutdownGracefully(recallExecutor);
            recallExecutor = null;
        }
```

- [ ] **Step 2: Edit `OnlinePredictionServer` shutdown hook (line 166)**

Replace:

```java
                activeRecallExecutor.shutdownNow();
```

with:

```java
                com.recsys.loadshed.GracefulExecutors.shutdownGracefully(activeRecallExecutor);
```

- [ ] **Step 3: Edit `OnlinePredictionServer` catch block (line 176)**

Replace:

```java
            if (recallExecutor != null) recallExecutor.shutdownNow();
```

with:

```java
            if (recallExecutor != null) com.recsys.loadshed.GracefulExecutors.shutdownGracefully(recallExecutor);
```

- [ ] **Step 4: Edit `RecSysServer` shutdown hook (line 152)**

Replace:

```java
                executor.shutdown();
```

with:

```java
                com.recsys.loadshed.GracefulExecutors.shutdownGracefully(executor);
```

- [ ] **Step 5: Build and run the full test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass (existing tests + Task 1's).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/model/ModelRuntimeProvider.java \
        src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
        src/main/java/com/recsys/api/serving/RecSysServer.java
git commit -m "fix(shutdown): drain recall executors gracefully instead of shutdownNow"
```

---

### Task 3: `retire-backend.sh` orchestration script

**Files:**
- Create: `scripts/retire-backend.sh`

**Interfaces:**
- Consumes: `kubectl` (required), `aws` (required only for Phase 4 cloud cleanup), the existing `/health/ready` + readiness behavior of the services.
- Produces: an executable, idempotent decommission script with phases drainable via `--dry-run`.

**Deployment names** (verified against `k8s/base/*.yaml` — deployments are `recsys-`-prefixed and each carries a matching `app:` label):
`GATEWAY_DEPLOY=recsys-api-gateway`, `BACKEND_DEPLOYS=(recsys-model-serving recsys-online-serving recsys-catalog-serving)`.
Note: `recsys-catalog-serving` is the RecSys offline serving deployment. Redis is two StatefulSets (`redis-primary`, `redis-replica`), both labeled `app: redis`, defined in `k8s/base/redis-cluster.yaml`.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Gracefully and irreversibly decommission the entire Recsys backend.
# Order: gateway drain -> backend drain -> infra teardown -> manifest delete -> verify.
# All runtime state is treated as disposable; there is NO backup step.
set -euo pipefail

NAMESPACE="recsys"
CONTEXT=""
DRY_RUN=false
ASSUME_YES=false
DRAIN_TIMEOUT=60
KEEP_INFRA=false

GATEWAY_DEPLOY="recsys-api-gateway"
BACKEND_DEPLOYS=(recsys-model-serving recsys-online-serving recsys-catalog-serving)
REDIS_LABEL="app=redis"   # redis-primary + redis-replica StatefulSets

usage() {
  cat <<'EOF'
Usage: retire-backend.sh [options]
  --namespace NS       Kubernetes namespace (default: recsys)
  --context CTX        kubectl context (default: current)
  --dry-run            Print commands without executing
  --yes                Skip the typed confirmation (for automation)
  --drain-timeout SEC  Max seconds to wait for a tier to drain (default: 60)
  --keep-infra         Stop before tearing down Redis/cloud infra
  -h, --help           Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --context) CONTEXT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --yes) ASSUME_YES=true; shift ;;
    --drain-timeout) DRAIN_TIMEOUT="$2"; shift 2 ;;
    --keep-infra) KEEP_INFRA=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

KCTX=()
[[ -n "$CONTEXT" ]] && KCTX=(--context "$CONTEXT")

run() {
  echo "+ $*"
  if [[ "$DRY_RUN" == false ]]; then
    "$@"
  fi
}

kc() { run kubectl ${KCTX[@]+"${KCTX[@]}"} -n "$NAMESPACE" "$@"; }

log() { echo "[retire] $*"; }

# Wait until a deployment has zero ready pods, bounded by DRAIN_TIMEOUT.
verify_drained() {
  local deploy="$1"
  log "verifying $deploy has drained (timeout ${DRAIN_TIMEOUT}s)"
  if [[ "$DRY_RUN" == true ]]; then
    echo "+ (dry-run) would poll '$deploy' until 0 ready pods or timeout"
    return 0
  fi
  local waited=0
  while true; do
    local ready
    ready="$(kubectl ${KCTX[@]+"${KCTX[@]}"} -n "$NAMESPACE" get deploy "$deploy" \
              -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo 0)"
    ready="${ready:-0}"
    local pods
    pods="$(kubectl ${KCTX[@]+"${KCTX[@]}"} -n "$NAMESPACE" get pods \
             -l app="$deploy" --no-headers 2>/dev/null | wc -l | tr -d ' ')"
    if [[ "$ready" == "0" && "$pods" == "0" ]]; then
      log "$deploy fully drained"
      return 0
    fi
    if (( waited >= DRAIN_TIMEOUT )); then
      echo "[retire] ERROR: $deploy did not drain within ${DRAIN_TIMEOUT}s" \
           "(readyReplicas=$ready, pods=$pods). Aborting — no force-kill." >&2
      exit 1
    fi
    sleep 5
    waited=$(( waited + 5 ))
  done
}

phase0_preflight() {
  log "PHASE 0: pre-flight"
  run kubectl ${KCTX[@]+"${KCTX[@]}"} config current-context
  kc get deploy -o wide || true
  log "All runtime state is DISPOSABLE. This is IRREVERSIBLE."
  if [[ "$ASSUME_YES" == false && "$DRY_RUN" == false ]]; then
    read -r -p "Type the namespace ('$NAMESPACE') to confirm retirement: " answer
    if [[ "$answer" != "$NAMESPACE" ]]; then
      echo "[retire] confirmation mismatch; aborting." >&2
      exit 1
    fi
  fi
}

phase1_gateway() {
  log "PHASE 1: drain gateway ($GATEWAY_DEPLOY)"
  kc scale deploy "$GATEWAY_DEPLOY" --replicas=0
  verify_drained "$GATEWAY_DEPLOY"
  log "Gateway drained — no new external traffic can reach any backend."
}

phase2_backends() {
  log "PHASE 2: drain backends (${BACKEND_DEPLOYS[*]})"
  for d in "${BACKEND_DEPLOYS[@]}"; do
    kc scale deploy "$d" --replicas=0
  done
  for d in "${BACKEND_DEPLOYS[@]}"; do
    verify_drained "$d"
  done
}

phase3_infra() {
  if [[ "$KEEP_INFRA" == true ]]; then
    log "PHASE 3: skipped (--keep-infra)"
    return 0
  fi
  log "PHASE 3: tear down infra (StatefulSets labeled $REDIS_LABEL)"
  log "NOTE: stop the Flink streaming jobs (separate repo Recsys-Streaming-Pipeline)" \
      "BEFORE their Kafka brokers — see runbook."
  kc delete statefulset -l "$REDIS_LABEL" --ignore-not-found
}

phase4_manifests() {
  log "PHASE 4: delete manifests"
  if [[ -d k8s/base ]]; then
    run kubectl ${KCTX[@]+"${KCTX[@]}"} delete -k k8s/base --ignore-not-found
  else
    log "k8s/base not found in CWD; delete manifests manually per runbook."
  fi
  log "Remove Cloud Map registrations / ALB target groups / IRSA per runbook (aws CLI)."
}

phase5_verify() {
  log "PHASE 5: verify clean state"
  kc get all || true
  log "Teardown report complete. Review output above for any remaining resources."
}

main() {
  phase0_preflight
  phase1_gateway
  phase2_backends
  phase3_infra
  phase4_manifests
  phase5_verify
  log "DONE."
}

main "$@"
```

- [ ] **Step 2: Make it executable**

Run: `chmod +x scripts/retire-backend.sh`

- [ ] **Step 3: Lint with shellcheck (if available) and verify dry-run ordering**

Run:
```bash
command -v shellcheck >/dev/null && shellcheck scripts/retire-backend.sh || echo "shellcheck not installed; skipping"
scripts/retire-backend.sh --dry-run --yes --namespace recsys | tee /tmp/retire-dryrun.txt
```
Expected: dry-run prints phases in order. Verify ordering with:
```bash
grep -n 'PHASE [0-5]' /tmp/retire-dryrun.txt
```
Expected: `PHASE 0` … `PHASE 5` appear in ascending order, and the gateway scale line precedes any backend scale line.

- [ ] **Step 4: Assert the invariant programmatically**

Run:
```bash
out="$(scripts/retire-backend.sh --dry-run --yes | grep -n 'scale deploy')"
echo "$out"
# api-gateway scale must appear before the first backend scale
gw_line=$(echo "$out" | grep -n 'api-gateway' | head -1 | cut -d: -f1)
be_line=$(echo "$out" | grep -n -E 'model-serving|online-serving|catalog-serving' | head -1 | cut -d: -f1)
[[ "$gw_line" -lt "$be_line" ]] && echo "ORDER OK" || { echo "ORDER VIOLATION" >&2; exit 1; }
```
Expected: `ORDER OK`.

- [ ] **Step 5: Commit**

```bash
git add scripts/retire-backend.sh
git commit -m "feat(ops): add ordered backend decommission script"
```

---

### Task 4: Operational runbook

**Files:**
- Create: `docs/runbooks/retire-backend.md`

**Interfaces:**
- Consumes: `scripts/retire-backend.sh` (Task 3).
- Produces: operator documentation; no code.

- [ ] **Step 1: Write the runbook**

```markdown
# Runbook: Retire (Decommission) the Recsys Backend

> **IRREVERSIBLE / POINT OF NO RETURN.** This procedure permanently shuts down all
> four backend services and their infrastructure. All runtime state (Redis
> embeddings, topk stores, online-learner params) is **discarded** — it is
> regenerable from the offline/streaming pipelines and is NOT backed up here.

## Prerequisites
- `kubectl` configured for the target cluster/context.
- `aws` CLI configured (for Cloud Map / ALB / IRSA cleanup).
- Confirmation that all external callers have been migrated off (the wind-down is
  graceful, but new traffic should already be gone).
- Confirmation that no consumer still depends on the live Redis/topk data.

## Procedure
The script `scripts/retire-backend.sh` automates the ordered teardown. Always do a
dry run first:

    scripts/retire-backend.sh --dry-run --namespace <ns> --context <ctx>

Then execute:

    scripts/retire-backend.sh --namespace <ns> --context <ctx>

### Phase mapping
| Script phase | What happens | Verify |
|---|---|---|
| 0 Pre-flight | Context check, replica snapshot, typed confirmation | You typed the namespace |
| 1 Gateway drain | `api-gateway` scaled to 0; ALB deregisters; in-flight drains | `verify_drained` passes; no external traffic reaches backends |
| 2 Backend drain | `model-/online-/catalog-serving` scaled to 0 in parallel; each flushes Kafka publishers, final learner flush, closes ONNX/Redis | `verify_drained` passes for each |
| 3 Infra teardown | Redis deleted (skipped with `--keep-infra`) | Redis resources gone |
| 4 Manifest delete | `kubectl delete -k k8s/base`; remove Cloud Map / ALB TG / IRSA | Manifests deleted |
| 5 Verify clean | `kubectl get all` report | No remaining pods/services |

### Streaming pipeline (separate repo)
The Flink streaming **jobs** live in `Recsys-Streaming-Pipeline`, NOT this repo.
Stop those jobs **before** their Kafka brokers, following that repo's own runbook.
The Kafka *publishers* inside this backend already flush on shutdown (Phase 2).

## If a tier won't drain
`verify_drained` aborts (no force-kill) if a tier exceeds `--drain-timeout`
(default 60s). Investigate the stuck pod (`kubectl describe`, logs). Once safe,
re-run the script — it is idempotent and resumes from where it stopped.

## Options
- `--keep-infra` — stop after Phase 2 (leave Redis/cloud infra up). Useful for a
  staged retirement: dry out the app tier first, tear down infra later.
- `--drain-timeout SEC` — adjust the per-tier drain budget.
- `--dry-run` — print every command without executing.

## Rollback
Before Phase 4 (manifest delete), retirement is reversible: scale the deployments
back up (`kubectl scale deploy <name> --replicas=N`). After Phase 4, redeploy from
`k8s/base` to restore the system (state will be empty and re-seed from the
pipelines).
```

- [ ] **Step 2: Verify it renders / no broken structure**

Run: `grep -c '^#' docs/runbooks/retire-backend.md`
Expected: at least one heading; eyeball the file for correctness.

- [ ] **Step 3: Commit**

```bash
git add docs/runbooks/retire-backend.md
git commit -m "docs(runbook): operational procedure for backend retirement"
```

---

## Self-Review Notes

- **Spec coverage:** Retirement sequence → Task 3 (all 6 phases) + Task 4 runbook. Graceful-executor fix → Tasks 1–2. No-new-endpoints constraint → honored (Task 3 reads existing readiness only). Testing strategy → Task 1 unit tests + Task 3 dry-run order assertion. Runbook → Task 4. All disposable / no backup → no export logic anywhere. ✓
- **Placeholder scan:** No TBD/TODO; all code shown in full. Deployment names flagged as "verify against `k8s/base/*.yaml`" with concrete defaults and a single edit point — this is a known-unknown about the cluster, not a plan placeholder. ✓
- **Type consistency:** `shutdownGracefully(ExecutorService)` / `(ExecutorService, Duration)` / `defaultTimeout()` used consistently across Tasks 1–2; helper class is `GracefulExecutors` throughout. ✓
```
