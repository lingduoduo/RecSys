# Docker Image Optimization for Faster Deploys — Design

**Date:** 2026-06-30
**Status:** Approved (pending spec review)
**Scope:** `Dockerfile`, `.dockerignore` only. No CI changes (repo has none). No application-code changes.

## Goal

Cut both CI/local **build time** and pod **pull + startup time** for the single shared
image that backs all four services (gateway, model-serving, online-serving,
catalog/recsys-serving). Land safe wins first; gate the riskier runtime slimming behind
real verification with a fallback.

## Context

- One multi-stage [`Dockerfile`](../../../Dockerfile) builds an image used by all four
  services; k8s selects the entrypoint per-service via the `RECSYS_MAIN_CLASS` env var.
- Build stage: `maven:3.9-amazoncorretto-17`. Runtime stage: `amazoncorretto:17-alpine`,
  running exploded `target/classes` + `target/dependency` (38 runtime jars) via classpath.
- Already optimal: multi-stage (no Maven in runtime image) and deps-before-classes layer
  ordering (dependency layer rebuilds only on `pom.xml` change). These are preserved.
- Docker 29.2.1 with default BuildKit is available locally — every phase is verifiable
  with a real `docker build` here. The standalone `buildx` plugin is absent but not
  needed (modern `docker build` uses BuildKit by default).

## Phase 1 — Build time + correctness (safe, no runtime change)

1. **`.m2` BuildKit cache mount.** Convert both Maven `RUN` steps to
   `RUN --mount=type=cache,target=/root/.m2 …`. Dependencies persist across builds
   regardless of `pom.xml` edits (today any pom change re-downloads all 38 deps into a
   busted layer). Drop the separate `dependency:go-offline` step — it is unreliable and
   the `package` step re-resolves anyway — removing one full Maven invocation.
2. **Fix stale default main class.** `Dockerfile` line ~17 defaults
   `RECSYS_MAIN_CLASS` to `com.recsys.microservice.MicroserviceGatewayServer`, a package
   that no longer exists. Change to `com.recsys.api.gateway.MicroserviceGatewayServer`.
   Production is unaffected (k8s overrides the env per service) but a bare `docker run`
   should be runnable.
3. **Tighten `.dockerignore`.** Add `recsys-architecture.*`, `docs/`, `.github/`,
   `.understand-anything/`, `.pytest_cache/`, `.vscode/`, `.codex/`, `.superpowers/` to
   shrink the build context sent to the daemon. (`src/test/`, `target/`, `streaming/`,
   `k8s/`, `*.md`, logs are already ignored.)

**Verify:** two successive `docker build`s; confirm the second reuses the cached `.m2`
(no re-download) and the gateway container starts with **no** env override.

## Phase 2 — AppCDS (conservative startup win, keeps alpine-corretto base)

In the build stage after `package`, generate **one shared** Class Data Sharing archive
over the common `classes` + `dependency` classpath (all four services share the same
classpath; only the main class differs). Mechanism: a short `-XX:ArchiveClassesAtExit=app.jsa`
training run that loads the shared classpath, then copy the single `app.jsa` into the
runtime image and add `-XX:SharedArchiveFile=app.jsa` to the launch command. Faster JVM startup →
faster pod readiness → faster rollouts. No native-library risk.

**Verify:** build, start each of the four main classes, confirm the CDS archive loads
(no `-Xshare` warnings) and startup logs are clean.

## Phase 3 — jlink slim JRE (biggest pull-size win, verified with fallback)

Separate runtime stage: `jdeps --print-module-deps` against the full classpath →
`jlink` a minimal JRE → base the runtime on a **slim alpine** image (decision below)
with that JRE copied in. Target ~70–100 MB vs ~170 MB.

**ONNX risk is explicit and gates this phase.** ONNX Runtime loads native `.so` libraries
and uses reflection / service-loading that `jdeps` can miss. Procedure:

1. Build the jlink image.
2. **Actually start `com.recsys.api.rest.ModelApplication`** (the ONNX service) and
   exercise a prediction; smoke-start the other three services.
3. If native libs or module resolution break → **fall back to the Phase-2 conservative
   image and stop.** No half-broken image ships.

**Base image decision:** stay on **alpine** (slim, retains a shell for debugging) rather
than distroless-static. Rationale: consistent with the phased/fallback posture and easier
to debug a broken ONNX native-lib path. Flip to distroless later if image size demands it.

## Cross-cutting

- Preserve the existing deps-before-classes layer split.
- One commit per phase so any phase can be reverted independently.
- Verification is local `docker build` + container start; no CI exists to change.

## Out of scope (YAGNI)

- ONNX model is 40 KB — no artifact-bloat work needed.
- No multi-image-per-service split; the shared image + per-service `RECSYS_MAIN_CLASS`
  stays.
- No unrelated refactoring of the Maven build or k8s manifests.
