# Deployment Simplification for Faster Deploys — Design

**Date:** 2026-07-02
**Status:** Approved (pending spec review)
**Scope:** `Dockerfile`, `k8s/base/kustomization.yaml`, and the k8s Deployment manifests
(`api-gateway.yaml`, `model-serving.yaml`, `online-serving.yaml`, `catalog-serving.yaml`).
No application-code changes. No CI changes (repo has none).

## Goal

Make deploys faster and simpler across three axes, building on the already-completed
3-phase Docker image optimization (BuildKit `.m2` cache mount, build-time AppCDS archive,
jlink glibc JRE on `debian:12-slim`):

1. **Faster image builds** — cut the largest recurring build cost.
2. **Simpler deploy step** — declare the image once instead of in four manifests.
3. **Faster k8s rollouts** — tighter probes + a less serial rollout strategy.

Each change is independently revertable (one commit each), verified locally, mirroring the
proven Phase 1-3 pattern.

## Context

- One multi-stage [`Dockerfile`](../../../Dockerfile) builds a single image used by all four
  services; k8s selects the entrypoint per-service via `RECSYS_MAIN_CLASS`.
- Runtime base is `debian:12-slim` with a jlink-trimmed glibc JRE (~100 MB image, already
  well-layered: `dependency/*` is a rarely-changing layer, `app-classes.jar` a tiny
  frequently-changing one — code-only changes push/pull a few KB).
- `k8s/base/` is a Kustomize base with per-service Deployment + Service manifests plus
  redis, pdb, hpa, network-policy, servicemonitor.

## What is deliberately NOT changed (already optimal / YAGNI)

- **Image layering, multi-stage build, deps-before-classes ordering, non-root user** —
  already optimal; untouched.
- **Further image slimming** (originally considered as a track) — **dropped.** On
  `debian:12-slim` the image is already minimal; the only remaining lever is trimming
  `jdk.localedata`/`jdk.jfr` from the jlink reflective-safety module set (~15 MB), which is
  the exact net that prevents runtime `NoClassDefFound`. 15 MB is <1s of pull on an already
  ~100 MB image — bad risk/reward. Base image stays `debian:12-slim` (keeps a shell for
  debugging the ONNX native path and `$JAVA_OPTS` shell expansion in the entrypoint);
  distroless was considered and rejected for that reason.
- **Full kustomize-component refactor to DRY the four Deployment bodies** — the manifests
  are readable as-is; a component refactor is a larger blast radius for little gain.
- **Shared-image / per-service `RECSYS_MAIN_CLASS` design** — kept.

## Track A — Cut the AppCDS build cost (biggest build win)

The Phase-3 AppCDS regeneration runs a training JVM on every build
([`Dockerfile:42-53`](../../../Dockerfile)):

```dockerfile
RUN (timeout -s TERM 40 java \
         -XX:DumpLoadedClassList=/app/app.classlist \
         -cp '/app/app-classes.jar:/app/dependency/*' com.recsys.api.gateway.MicroserviceGatewayServer \
         || true) \
    && test -s /app/app.classlist \
    && java -Xshare:dump ...
```

The gateway binds and loads its framework/app classes within a few seconds but never
self-exits, so `timeout -s TERM 40` **always** burns the full ~40s on every build.

**Change:** reduce the timeout `40` → `15`. The class-loading bulk completes well inside
15s; the timeout is only an upper bound on a server that won't exit on its own.

**Preserved guarantees:**
- The `test -s /app/app.classlist` guard stays — an empty classlist still fails the build.
- `-Xshare:auto` at runtime stays — a smaller/incompatible archive silently falls back,
  never hard-fails a service.

**Net:** ~25s off every image build.

**Failure mode & verify:** if 15s is too short, fewer classes are captured → a slightly
smaller CDS archive → marginally less startup benefit, never a build/runtime failure.
Verify by building, then running the gateway with `-Xlog:cds=info` and confirming the
archive still maps and captures a class count comparable to the 40s baseline.

## Track C — Simpler deploy step (kustomize image transformer)

The image `recsys-backend-service:local` is hardcoded in all four Deployment manifests
([`model-serving.yaml:44`](../../../k8s/base/model-serving.yaml),
[`api-gateway.yaml:32`](../../../k8s/base/api-gateway.yaml), and the other two), and
[`k8s/base/kustomization.yaml`](../../../k8s/base/kustomization.yaml) has no `images:`
block — so bumping the deployed image tag today means editing four files.

**Change:** add an `images:` transformer to `k8s/base/kustomization.yaml`:

```yaml
images:
  - name: recsys-backend-service
    newTag: local
```

The manifests keep `image: recsys-backend-service:local` as the match target; the
transformer overrides the tag centrally. Deploying a new build becomes a **one-line**
`newTag:` edit (or a `kustomize edit set image` invocation) instead of a four-file change.

**Verify:** `kustomize build k8s/base` (or `kubectl kustomize k8s/base`) renders all four
Deployments with the transformer-supplied tag; diff against the pre-change render shows
only the intended image references, nothing else moved.

## Track D — Faster k8s rollouts (probe + strategy tuning)

Now that AppCDS + the jlink JRE speed JVM startup, the probe cadence and rollout strategy
can be tightened without risking flapping.

**Changes:**

1. **model-serving rollout** ([`model-serving.yaml:11-15`](../../../k8s/base/model-serving.yaml)):
   `maxSurge: 1` → `2`, keeping `maxUnavailable: 0`. A 3-replica rollout completes in 2
   waves instead of 3, with zero capacity loss during the roll.

2. **Startup + readiness probe cadence** (all four services): `periodSeconds: 10` → `5` on
   `startupProbe` and `readinessProbe`, with `startupProbe.failureThreshold` **doubled** so
   the overall startup budget is unchanged:
   - model-serving startup: `18 × 10s` → `36 × 5s` = 180s budget preserved.
   - api-gateway startup: `12 × 10s` → `24 × 5s` = 120s budget preserved.
   - online-serving startup (`12 × 10s`) → `24 × 5s` = 120s budget preserved.
   - catalog-serving startup (`12 × 10s`) → `24 × 5s` = 120s budget preserved.

   (Only `model-serving` has an explicit restrictive rollout strategy today; the other
   three keep the default RollingUpdate — Track D change 1 applies to model-serving only.)

   Effect: a pod that becomes ready at ~12s flips Ready in ~15s instead of ~20s, and joins
   the rollout sooner.

3. **livenessProbe** stays conservative (`periodSeconds: 20`) — no benefit to making
   liveness aggressive, and doing so risks killing healthy-but-busy pods.

**Verify:** `kustomize build k8s/base` renders cleanly; per-service, confirm
`startupProbe.failureThreshold × periodSeconds` still equals the prior budget (no
accidental shortening that could kill a legitimately slow cold start).

## Cross-cutting

- One commit per track (A, C, D) so any can be reverted independently.
- Verification is local: `docker build` + container smoke-start for Track A;
  `kustomize build k8s/base` for Tracks C and D. No CI exists to change.
- Track B (further image slimming) is intentionally absent — see the NOT-changed section.

## Out of scope (YAGNI)

- Distroless base / jlink module trimming (bad risk/reward on the current image).
- Full kustomize-component DRY refactor of the four Deployment bodies.
- Any application-code, Maven-build, redis, hpa, pdb, network-policy, or servicemonitor
  changes.
