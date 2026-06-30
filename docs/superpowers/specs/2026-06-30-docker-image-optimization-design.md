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

## Phase 3 — jlink slim JRE on a glibc base (slim win **and** ONNX fix)

> **Revised after a Phase-2 discovery (2026-06-30).** The original plan kept an
> alpine (musl) base. During Phase-2 verification we found that ONNX Runtime's
> `libonnxruntime.so` is **glibc-built** — it requires `ld-linux-aarch64.so.1` and
> `libstdc++.so.6`, neither of which exists on musl/alpine. On `amazoncorretto:17-alpine`,
> `com.recsys.api.rest.ModelApplication` starts Tomcat and then crashes (exit 1) the moment
> `ModelRuntimeProvider` warms the ONNX runtime (`UnsatisfiedLinkError`). This is a
> **pre-existing bug**: the original Dockerfile used the same alpine base, so the
> k8s `model-serving` deployment has been crash-looping on ONNX init independent of this work.
> Adding `libstdc++` only surfaces the next glibc dependency (`ld-linux`), so there is no
> alpine-only fix. **Decision (user-approved): move the runtime base to glibc**, which both
> delivers the slim-image goal and fixes the ONNX crash in one move.

Separate runtime: `jdeps --print-module-deps` against the full classpath → `jlink` a
minimal JRE **from the `maven:3.9-amazoncorretto-17` build stage (already glibc)** → base
the runtime on **`debian:12-slim`** (glibc, ships `libstdc++6`/`libgcc-s`, retains a shell
for debugging) with that JRE copied in. Target ~90–120 MB vs the broken ~170 MB alpine image.

Because the runtime JVM changes from the musl alpine JVM to a glibc jlink JRE, the Phase-2
AppCDS archive (generated on musl) would be rejected cross-build — so **the AppCDS archive
is regenerated in Phase 3 on the glibc JVM** (same two-step static-CDS mechanism Phase 2
settled on, run against `app-classes.jar` + `dependency/*`).

**ONNX is now a positive gate, not a risk-of-regression.** Procedure:

1. Build the jlink/debian-slim image.
2. **Actually start `com.recsys.api.rest.ModelApplication`** and confirm ONNX now loads
   (no `UnsatisfiedLinkError`) and a prediction works; smoke-start the other three services.
3. If `jdeps` missed a reflectively-loaded module, add it to the explicit module set and
   rebuild. The base change is the whole point of this phase — there is no "fall back to
   the alpine image," because that image is the broken one.

**Base image decision:** **`debian:12-slim`** (glibc, has a shell). Rejected alternatives:
alpine+`gcompat` (unreliable for ONNX), `distroless/cc-debian12` (smaller but no shell —
harder to debug the native-lib path during this very change).

## Cross-cutting

- Preserve the existing deps-before-classes layer split.
- One commit per phase so any phase can be reverted independently.
- Verification is local `docker build` + container start; no CI exists to change.

## Out of scope (YAGNI)

- ONNX model is 40 KB — no artifact-bloat work needed.
- No multi-image-per-service split; the shared image + per-service `RECSYS_MAIN_CLASS`
  stays.
- No unrelated refactoring of the Maven build or k8s manifests.
