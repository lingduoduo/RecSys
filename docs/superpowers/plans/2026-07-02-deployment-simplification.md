# Deployment Simplification for Faster Deploys Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make deploys faster and simpler in three independently-revertable moves: cut the biggest recurring image-build cost, declare the deploy image once instead of in four manifests, and tighten k8s probes/rollout so pods roll faster.

**Architecture:** Three isolated changes on top of the completed 3-phase Docker optimization. Task A edits one number in the `Dockerfile` AppCDS step. Task C adds a Kustomize `images:` transformer. Task D tightens probe cadence and the model-serving rollout strategy across the four service manifests. No application code, no CI (repo has none).

**Tech Stack:** Docker (BuildKit, Docker 29.x), Amazon Corretto 17 / jlink / AppCDS, Kubernetes, Kustomize (via `kubectl kustomize`, embedded v5.7.1).

## Global Constraints

- Edit only `Dockerfile` and files under `k8s/base/`. No application-code changes. No CI changes (repo has none).
- One commit per task (A, C, D) so each is independently revertable. Never commit to `main`; stay on branch `feat/backend-retirement`.
- Every task must leave the image buildable and the Kustomize base renderable.
- Preserve the existing `test -s /app/app.classlist` build guard and runtime `-Xshare:auto` fallback (Task A).
- Preserve `maxUnavailable: 0` (zero-downtime) on the model-serving rollout (Task D).
- Preserve each service's total startup-probe budget when changing probe cadence: `failureThreshold × periodSeconds` must stay constant (Task D).
- Verification is local: `docker build` + container smoke-start (Task A); `kubectl kustomize k8s/base` (Tasks C and D). The standalone `kustomize` binary is absent — use `kubectl kustomize`.

---

### Task A: Cut the AppCDS training timeout (faster builds)

**Files:**
- Modify: `Dockerfile:42` (the `timeout -s TERM 40` in the runtime-stage AppCDS regen block)

**Interfaces:**
- Consumes: the existing runtime-stage AppCDS block (`Dockerfile:42-53`) producing `/app/app.jsa`.
- Produces: an identical `/app/app.jsa` artifact and identical entrypoint, generated with a shorter training window.

- [ ] **Step 1: Reduce the training timeout from 40s to 15s**

In `Dockerfile`, change the AppCDS regeneration block. Replace this exact line (`Dockerfile:42`):

```dockerfile
    RUN (timeout -s TERM 40 java \
```

with:

```dockerfile
    RUN (timeout -s TERM 15 java \
```

Leave every other line of the block unchanged — the `-XX:DumpLoadedClassList`, the `test -s /app/app.classlist` guard, the `java -Xshare:dump` step, the `chown`, and the `ENTRYPOINT` all stay exactly as they are. The gateway loads its framework/app classes within a few seconds; 15s is ample headroom, and the timeout is only an upper bound on a server that never self-exits.

- [ ] **Step 2: Build the image (this is the test)**

Run:

```bash
docker build -t recsys:deploy-a .
```

Expected: build succeeds, including the `test -s /app/app.classlist` guard (a non-empty classlist was produced within 15s) and the `java -Xshare:dump` step (the `.jsa` archive was written). If the build fails at `test -s /app/app.classlist`, 15s was too short to load any classes — bump to 20s and rebuild.

- [ ] **Step 3: Verify the CDS archive still loads and captures a comparable class count**

Run the gateway with CDS logging and confirm the shared archive is mapped (not rejected):

```bash
docker rm -f recsys-a 2>/dev/null
docker run --rm -d --name recsys-a -e JAVA_OPTS="-Xlog:cds=info" recsys:deploy-a
sleep 8
docker logs recsys-a 2>&1 | grep -iE "cds|shared|archive|Exception in thread" | head -20
docker rm -f recsys-a
```

Expected: the CDS log shows the archive opened/mapped (e.g. "Opened archive /app/app.jsa" / "Mapped ... shared"), NO archive-incompatibility rejection, and NO `Exception in thread` on the entrypoint class. A materially smaller captured-class count than the 40s baseline is acceptable (it only trims the startup benefit, never correctness) — but a fully rejected/absent archive means investigate before committing.

- [ ] **Step 4: Commit**

```bash
git add Dockerfile
git commit -m "perf(docker): cut AppCDS training timeout 40s->15s for faster builds

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task C: Kustomize image transformer (one-line deploys)

**Files:**
- Modify: `k8s/base/kustomization.yaml` (append an `images:` block)

**Interfaces:**
- Consumes: the four Deployment manifests, each with `image: recsys-backend-service:local` as the match target (unchanged).
- Produces: a single authoritative image tag in `kustomization.yaml`; bumping the deployed tag becomes a one-line `newTag:` edit.

- [ ] **Step 1: Capture the pre-change render as a baseline**

Render the base once before editing so Step 3 can diff against it:

```bash
kubectl kustomize k8s/base > /tmp/kustomize-before.yaml
grep -c 'image: recsys-backend-service:local' /tmp/kustomize-before.yaml
```

Expected: the `grep -c` prints `4` (all four Deployments reference the image).

- [ ] **Step 2: Add the `images:` transformer**

Append to the end of `k8s/base/kustomization.yaml` (after the existing `resources:` list):

```yaml
images:
  - name: recsys-backend-service
    newTag: local
```

The manifests keep their literal `image: recsys-backend-service:local`; the transformer matches on the `recsys-backend-service` name and supplies the tag centrally. To deploy a new build later, edit the single `newTag:` value (or run `kubectl kustomize edit set image recsys-backend-service=recsys-backend-service:<tag>` from `k8s/base`).

- [ ] **Step 3: Verify the render is byte-identical except intentionally**

```bash
kubectl kustomize k8s/base > /tmp/kustomize-after.yaml
diff /tmp/kustomize-before.yaml /tmp/kustomize-after.yaml && echo "IDENTICAL"
grep -c 'image: recsys-backend-service:local' /tmp/kustomize-after.yaml
```

Expected: `diff` reports **no differences** and prints `IDENTICAL` (the transformer reproduces the same `:local` tag that was hardcoded, proving it is wired to the right image name without changing the deployed result), and the `grep -c` still prints `4`. If `diff` shows changes other than image tags, or the count is not `4`, the `name:` did not match — fix the `name:` value and re-render.

- [ ] **Step 4: Commit**

```bash
git add k8s/base/kustomization.yaml
git commit -m "refactor(k8s): centralize image tag via kustomize images transformer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task D: Tighten probes and rollout strategy (faster rollouts)

**Files:**
- Modify: `k8s/base/model-serving.yaml` (rollout `maxSurge`; startup + readiness `periodSeconds`; startup `failureThreshold`)
- Modify: `k8s/base/api-gateway.yaml` (startup + readiness `periodSeconds`; startup `failureThreshold`)
- Modify: `k8s/base/online-serving.yaml` (startup + readiness `periodSeconds`; startup `failureThreshold`)
- Modify: `k8s/base/catalog-serving.yaml` (startup + readiness `periodSeconds`; startup `failureThreshold`)

**Interfaces:**
- Consumes: the four Deployment manifests with current probe values (model-serving startup `18×10s`; the other three startup `12×10s`; all readiness `periodSeconds: 10`; all liveness `periodSeconds: 20`).
- Produces: the same startup budgets (`failureThreshold × periodSeconds` unchanged) with `periodSeconds: 5` on startup + readiness, plus `maxSurge: 2` on model-serving. Liveness untouched.

- [ ] **Step 1: model-serving — bump maxSurge to 2**

In `k8s/base/model-serving.yaml`, in the `spec.strategy.rollingUpdate` block, change:

```yaml
      maxUnavailable: 0
      maxSurge: 1
```

to:

```yaml
      maxUnavailable: 0
      maxSurge: 2
```

`maxUnavailable: 0` stays (zero-downtime); `maxSurge: 2` lets a 3-replica rollout complete in 2 waves instead of 3.

- [ ] **Step 2: model-serving — tighten startup + readiness cadence**

In `k8s/base/model-serving.yaml`, change the `startupProbe` block:

```yaml
          startupProbe:
            httpGet:
              path: /health/ready
              port: http
            failureThreshold: 18
            periodSeconds: 10
```

to:

```yaml
          startupProbe:
            httpGet:
              path: /health/ready
              port: http
            failureThreshold: 36
            periodSeconds: 5
```

(`36 × 5s` = 180s, same budget as `18 × 10s`.) Then change the `readinessProbe` block:

```yaml
          readinessProbe:
            httpGet:
              path: /health/ready
              port: http
            periodSeconds: 10
            failureThreshold: 3
```

to:

```yaml
          readinessProbe:
            httpGet:
              path: /health/ready
              port: http
            periodSeconds: 5
            failureThreshold: 3
```

Leave the `livenessProbe` (`periodSeconds: 20`) unchanged.

- [ ] **Step 3: api-gateway — tighten startup + readiness cadence**

In `k8s/base/api-gateway.yaml`, change the `startupProbe` block:

```yaml
          startupProbe:
            httpGet:
              path: /health
              port: http
            failureThreshold: 12
            periodSeconds: 10
```

to:

```yaml
          startupProbe:
            httpGet:
              path: /health
              port: http
            failureThreshold: 24
            periodSeconds: 5
```

(`24 × 5s` = 120s, same as `12 × 10s`.) Then change the `readinessProbe` block:

```yaml
          readinessProbe:
            httpGet:
              path: /health
              port: http
            periodSeconds: 10
```

to:

```yaml
          readinessProbe:
            httpGet:
              path: /health
              port: http
            periodSeconds: 5
```

Leave the `livenessProbe` (`periodSeconds: 20`) unchanged.

- [ ] **Step 4: online-serving — tighten startup + readiness cadence**

In `k8s/base/online-serving.yaml`, change the `startupProbe` block:

```yaml
          startupProbe:
            httpGet:
              path: /health/ready
              port: http
            failureThreshold: 12
            periodSeconds: 10
```

to:

```yaml
          startupProbe:
            httpGet:
              path: /health/ready
              port: http
            failureThreshold: 24
            periodSeconds: 5
```

Then change the `readinessProbe` block:

```yaml
          readinessProbe:
            httpGet:
              path: /health/ready
              port: http
            periodSeconds: 10
```

to:

```yaml
          readinessProbe:
            httpGet:
              path: /health/ready
              port: http
            periodSeconds: 5
```

Leave the `livenessProbe` (`periodSeconds: 20`) unchanged.

- [ ] **Step 5: catalog-serving — tighten startup + readiness cadence**

In `k8s/base/catalog-serving.yaml`, change the `startupProbe` block:

```yaml
          startupProbe:
            httpGet:
              path: /health
              port: http
            failureThreshold: 12
            periodSeconds: 10
```

to:

```yaml
          startupProbe:
            httpGet:
              path: /health
              port: http
            failureThreshold: 24
            periodSeconds: 5
```

Then change the `readinessProbe` block:

```yaml
          readinessProbe:
            httpGet:
              path: /health
              port: http
            periodSeconds: 10
```

to:

```yaml
          readinessProbe:
            httpGet:
              path: /health
              port: http
            periodSeconds: 5
```

Leave the `livenessProbe` (`periodSeconds: 20`) unchanged.

- [ ] **Step 6: Verify the base renders and the budgets are preserved**

```bash
kubectl kustomize k8s/base > /tmp/kustomize-d.yaml && echo "RENDER OK"
grep -nE "maxSurge|failureThreshold|periodSeconds" /tmp/kustomize-d.yaml
```

Expected: `RENDER OK` (no YAML/schema error). In the grep output confirm: one `maxSurge: 2`; the startup `failureThreshold` values are `36` (model-serving) and `24` (the other three) each paired with `periodSeconds: 5`; readiness probes show `periodSeconds: 5`; liveness probes still show `periodSeconds: 20`. Spot-check each service's startup budget: `36×5=180` (model-serving), `24×5=120` (others) — matching the pre-change `18×10` and `12×10`.

- [ ] **Step 7: Commit**

```bash
git add k8s/base/model-serving.yaml k8s/base/api-gateway.yaml k8s/base/online-serving.yaml k8s/base/catalog-serving.yaml
git commit -m "perf(k8s): faster rollouts via tighter probe cadence and model-serving maxSurge

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Track A (cut AppCDS build cost, `40`→`15`, preserve guard + `-Xshare:auto`) → Task A. ✓
- Track C (kustomize `images:` transformer, one-line deploys) → Task C. ✓
- Track D (model-serving `maxSurge 1→2` with `maxUnavailable: 0` kept; startup+readiness `periodSeconds 10→5`; `failureThreshold` doubled to preserve budgets; liveness untouched; strategy change only on model-serving) → Task D. ✓
- Track B intentionally absent → matches the spec's NOT-changed section. ✓
- Cross-cutting: one commit per track (each task's final Step); local verification only (`docker build`/smoke + `kubectl kustomize`); branch `feat/backend-retirement`. ✓

**Placeholder scan:** no TBD/TODO; every edit shows exact before/after YAML or the exact Dockerfile line; every verify step is a concrete command with expected output. ✓

**Value/consistency check:** startup budgets preserved — model-serving `18×10=180`→`36×5=180`; api-gateway/online/catalog `12×10=120`→`24×5=120`. `maxUnavailable: 0` preserved on the only manifest that sets a strategy. Image match name `recsys-backend-service` matches the `image:` in all four manifests (`recsys-backend-service:local`). ✓
