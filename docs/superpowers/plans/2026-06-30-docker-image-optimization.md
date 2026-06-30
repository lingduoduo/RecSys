# Docker Image Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut CI/local build time and pod pull+startup time for the single shared image backing all four services, landing safe wins first and gating the riskier runtime slimming behind real verification.

**Architecture:** Three independent, individually-revertable changes to `Dockerfile` + `.dockerignore`. Phase 1 speeds builds (BuildKit `.m2` cache mount) and fixes the stale default main class. Phase 2 adds a build-time AppCDS archive for faster JVM startup, keeping the alpine-corretto runtime base. Phase 3 replaces the runtime JRE with a `jlink`-trimmed musl JRE on a slim alpine base, verified against the ONNX service before it ships.

**Tech Stack:** Docker (BuildKit, default in Docker 29.x), multi-stage builds, Maven 3.9 / Amazon Corretto 17, AppCDS (dynamic CDS), `jdeps`/`jlink`.

## Global Constraints

- Edit only `Dockerfile` and `.dockerignore`. No application-code changes. No CI changes (repo has none).
- Java 17 (Amazon Corretto). Build stage `maven:3.9-amazoncorretto-17` (glibc). **Phase 3 runtime base is `debian:12-slim` (glibc)** — required because ONNX Runtime's native lib is glibc-built and cannot run on musl/alpine. jlink runs in the glibc build stage so the trimmed JRE matches the glibc final base (libc must match between jlink JVM and runtime base).
- Preserve the existing deps-before-classes layer ordering (dependency layer must rebuild only on `pom.xml` change).
- Preserve the non-root `recsys` user, `EXPOSE 8010`, `JAVA_OPTS` passthrough, and per-service `RECSYS_MAIN_CLASS` override mechanism (k8s sets it; the Dockerfile only supplies a runnable default).
- One commit per phase. Each phase must leave a buildable, runnable image.
- Verification is local `docker build` + container smoke-start. Docker daemon is available; BuildKit is the default builder.

---

### Task 1: Phase 1 — BuildKit `.m2` cache mount, correct default main class, tighter `.dockerignore`

**Files:**
- Modify: `Dockerfile:4-8` (Maven steps), `Dockerfile:17` (default main class)
- Modify: `.dockerignore` (append context-excludes)

**Interfaces:**
- Consumes: existing build stage producing `/workspace/target/classes` and `/workspace/target/dependency`.
- Produces: identical `target/classes` + `target/dependency` layout for the runtime stage (later phases depend on these exact paths). Default `RECSYS_MAIN_CLASS=com.recsys.api.gateway.MicroserviceGatewayServer`.

- [ ] **Step 1: Rewrite the build-stage Maven steps to use a `.m2` cache mount**

Replace `Dockerfile` lines 4–8 (current):

```dockerfile
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime
```

with:

```dockerfile
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime
```

Rationale: the persistent `.m2` cache mount makes the separate `dependency:go-offline` warm-up redundant (it was unreliable and `package` re-resolves anyway), removing one full Maven invocation. The cache survives `pom.xml` changes, so only changed deps re-download.

- [ ] **Step 2: Fix the stale default main class**

Change `Dockerfile:17` from:

```dockerfile
ENV RECSYS_MAIN_CLASS=com.recsys.microservice.MicroserviceGatewayServer
```

to:

```dockerfile
ENV RECSYS_MAIN_CLASS=com.recsys.api.gateway.MicroserviceGatewayServer
```

(The `com.recsys.microservice` package no longer exists; the gateway class is `com.recsys.api.gateway.MicroserviceGatewayServer`. k8s overrides this env per service, so production is unaffected — this only makes a bare `docker run` runnable.)

- [ ] **Step 3: Tighten `.dockerignore`**

Append to `.dockerignore`:

```
recsys-architecture.*
docs/
.github/
.understand-anything/
.pytest_cache/
.vscode/
.codex/
.superpowers/
config/
```

(These are never COPY'd into the image but are otherwise sent to the daemon as build context. `src/test/`, `target/`, `streaming/`, `k8s/`, `*.md`, `*.log` are already ignored.)

- [ ] **Step 4: Build the image (this is the test)**

Run:

```bash
docker build -t recsys:phase1 .
```

Expected: build succeeds. The first build populates the `.m2` cache. Note the dependency-download step output.

- [ ] **Step 5: Re-build to verify the cache mount is reused**

Touch `pom.xml` to bust the layer cache but keep the `.m2` mount warm, then rebuild:

```bash
touch pom.xml && docker build -t recsys:phase1 . 2>&1 | tail -20
```

Expected: the Maven step re-runs (pom changed) but does NOT re-download dependencies from scratch — Maven reports artifacts resolved from the local `.m2` cache, and the step completes substantially faster than Step 4's cold run.

- [ ] **Step 6: Smoke-start the gateway with NO env override (verifies the main-class fix)**

Run:

```bash
docker run --rm --name recsys-p1 -d recsys:phase1
sleep 8
docker logs recsys-p1 2>&1 | grep -iE "Exception in thread|ClassNotFound|NoClassDefFound|gateway|8010" | head -20
docker rm -f recsys-p1
```

Expected: NO `ClassNotFoundException` / `NoClassDefFoundError` for the main class. The gateway either logs startup/listening on 8010 or fails only on a downstream dependency (e.g. Redis/route targets) — never on the entrypoint class itself.

- [ ] **Step 7: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "perf(docker): cache .m2 across builds, fix default main class, trim context

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Phase 2 — Build-time AppCDS archive for faster JVM startup

**Files:**
- Modify: `Dockerfile` (build stage: add archive-generation step; runtime stage: copy `.jsa`, add `-XX:SharedArchiveFile` to entrypoint)

**Interfaces:**
- Consumes: `/workspace/target/classes` + `/workspace/target/dependency` from the build stage (Task 1).
- Produces: `/app/app.jsa` in the runtime image and a launch command that loads it via `-XX:SharedArchiveFile=/app/app.jsa -Xshare:auto`.

- [ ] **Step 1: Add an AppCDS training step to the build stage**

After the `package` step in the build stage (the `RUN --mount=...mvn...` line from Task 1), add:

```dockerfile
# Generate one shared AppCDS archive over the common classpath. All four services
# share classes+dependency; only the main class differs. A short timeout-bounded
# training run of the gateway loads the framework/app classes; -XX:ArchiveClassesAtExit
# dumps the archive during JVM shutdown (triggered by timeout's SIGTERM).
RUN cd /workspace/target \
    && (timeout -s TERM 40 java -XX:ArchiveClassesAtExit=/workspace/app.jsa \
         -cp 'classes:dependency/*' com.recsys.api.gateway.MicroserviceGatewayServer \
         || true) \
    && test -s /workspace/app.jsa
```

Rationale: `-XX:ArchiveClassesAtExit` writes the dynamic CDS archive on JVM shutdown. The gateway binds without external services, so it loads the bulk of the shared framework/app classes during startup; `timeout -s TERM` ends the run after 40s and the archive is written in the shutdown path. `test -s` fails the build if no archive was produced.

- [ ] **Step 2: Copy the archive into the runtime image**

In the runtime stage, after the existing `COPY --from=build /workspace/target/dependency /app/dependency` line, add:

```dockerfile
COPY --from=build /workspace/app.jsa /app/app.jsa
```

- [ ] **Step 3: Wire the archive into the entrypoint**

Change the `ENTRYPOINT` line so the JVM loads the archive (`-Xshare:auto` falls back silently if the archive is incompatible, so this never hard-fails a service):

```dockerfile
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:SharedArchiveFile=/app/app.jsa -Xshare:auto -cp /app/classes:/app/dependency/* $RECSYS_MAIN_CLASS"]
```

- [ ] **Step 4: Build the image (test)**

Run:

```bash
docker build -t recsys:phase2 .
```

Expected: build succeeds, including the `test -s /workspace/app.jsa` guard (archive was produced).

- [ ] **Step 5: Verify the archive actually loads at runtime**

Run each service entrypoint briefly with CDS logging and confirm the shared archive is mapped (not rejected). Gateway example:

```bash
docker run --rm -d --name recsys-p2 -e JAVA_OPTS="-Xlog:cds=info" recsys:phase2
sleep 8
docker logs recsys-p2 2>&1 | grep -iE "cds|shared|Exception in thread" | head -20
docker rm -f recsys-p2
```

Expected: CDS log shows the archive mapped/used (e.g. "Mapped ... shared" / "Opened archive app.jsa"), and NO archive-incompatibility rejection. Repeat the smoke-start for one more service to confirm the shared archive is accepted across main classes:

```bash
docker run --rm -d --name recsys-p2b -e RECSYS_MAIN_CLASS=com.recsys.api.rest.ModelApplication recsys:phase2
sleep 12
docker logs recsys-p2b 2>&1 | grep -iE "Exception in thread|started|Tomcat|8080" | head -20
docker rm -f recsys-p2b
```

Expected: model-serving starts without an entrypoint-class or CDS error.

- [ ] **Step 6: Commit**

```bash
git add Dockerfile
git commit -m "perf(docker): add build-time AppCDS archive for faster JVM startup

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

**Fallback:** if Step 1's `test -s` fails (gateway crashes before loading classes) or Step 5 shows the archive rejected for every service, revert this task (`git checkout Dockerfile`) and stop at Phase 1 — AppCDS is the optional middle phase, not a prerequisite for Phase 3.

---

### Task 3: Phase 3 — jlink-trimmed glibc JRE on `debian:12-slim` (slim win + ONNX fix)

> **Revised after the Phase-2 ONNX discovery.** ONNX Runtime's `libonnxruntime.so` is
> glibc-built (needs `ld-linux-aarch64.so.1` + `libstdc++.so.6`) and cannot run on
> musl/alpine — `com.recsys.api.rest.ModelApplication` crashes on the current
> `amazoncorretto:17-alpine` base with `UnsatisfiedLinkError`. This task moves the runtime
> to a **glibc** base, which is the whole point: it makes the image smaller AND fixes the
> pre-existing model-serving crash. jlink runs in the already-glibc `maven` build stage.
> The Phase-2 AppCDS archive (musl) is regenerated here on the glibc JVM.

**Files:**
- Modify: `Dockerfile` (add jlink to the build stage; replace the runtime base with `debian:12-slim` + jlinked JRE; regenerate AppCDS on the glibc JVM)

**Interfaces:**
- Consumes (from the `build` stage): `/workspace/app-classes.jar`, `/workspace/target/dependency`, and a new `/opt/jre` (the jlinked JRE).
- Produces: a `debian:12-slim` runtime image with `/opt/jre` on PATH, `/app/app-classes.jar`, `/app/dependency/`, and a freshly generated `/app/app.jsa`. Entrypoint `java` resolves to `/opt/jre/bin/java`.

- [ ] **Step 1: Add a jlink step to the (glibc) build stage**

The `maven:3.9-amazoncorretto-17` build stage is already glibc and has `jdeps`/`jlink`. After the existing `RUN jar cf /workspace/app-classes.jar ...` line, add:

```dockerfile
# Build a minimal glibc JRE with jlink (this stage is glibc, matching the runtime base).
# Detect modules from the real classpath, then union a safety set for reflectively-loaded
# modules jdeps cannot see (crypto, JNDI, JDBC, management, instrumentation, locales) that
# Spring and ONNX Runtime rely on.
RUN set -eux; \
    MODS="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 17 \
              -cp '/workspace/target/dependency/*' --recursive /workspace/app-classes.jar 2>/dev/null || echo java.base)"; \
    MODS="${MODS},jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,java.naming,java.management,java.sql,jdk.zipfs,java.security.jgss,java.instrument,jdk.jfr,jdk.localedata"; \
    jlink --add-modules "$MODS" --strip-debug --no-man-pages --no-header-files \
          --compress=2 --output /opt/jre; \
    /opt/jre/bin/java -version
```

- [ ] **Step 2: Replace the runtime stage base, install ONNX's glibc deps, copy the JRE**

Replace the runtime stage header (currently `FROM amazoncorretto:17-alpine` + `WORKDIR /app` + `RUN addgroup -S recsys && adduser -S -G recsys recsys` + the two `COPY --from=build ...` lines) with:

```dockerfile
FROM debian:12-slim
WORKDIR /app

# glibc C++/OpenMP runtime libs that ONNX Runtime's native .so needs (libc6/libgcc-s1 are
# already in the base; libstdc++6 + libgomp1 are not). This is what fixes the ONNX crash.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libstdc++6 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r recsys && useradd -r -g recsys recsys

COPY --from=build /opt/jre /opt/jre
ENV PATH="/opt/jre/bin:${PATH}"

COPY --from=build /workspace/app-classes.jar /app/app-classes.jar
COPY --from=build /workspace/target/dependency /app/dependency
```

- [ ] **Step 3: Regenerate the AppCDS archive on the glibc JVM**

The Phase-2 archive was built on the musl JVM and would be rejected here. Keep the same
two-step static-CDS mechanism (it already targets the JAR classpath with `/app` paths so
generation and runtime match) — it now runs on the jlinked glibc JVM. The existing CDS
`RUN` block stays, but its `chown` only needs `/app/app.jsa` (the jar is copied as root
and is world-readable). Ensure the runtime-stage CDS block reads:

```dockerfile
# Regenerate the shared AppCDS archive with the glibc jlink JVM (the musl Phase-2 archive
# would be rejected cross-build). -Xshare:auto at runtime falls back silently if incompatible.
RUN (timeout -s TERM 40 java \
         -XX:DumpLoadedClassList=/app/app.classlist \
         -cp '/app/app-classes.jar:/app/dependency/*' com.recsys.api.gateway.MicroserviceGatewayServer \
         || true) \
    && test -s /app/app.classlist \
    && java -Xshare:dump \
         -XX:SharedClassListFile=/app/app.classlist \
         -XX:SharedArchiveFile=/app/app.jsa \
         -cp '/app/app-classes.jar:/app/dependency/*' \
    && rm -f /app/app.classlist \
    && test -s /app/app.jsa \
    && chown recsys:recsys /app/app.jsa
```

The `ENV RECSYS_MAIN_CLASS=...`, `ENV JAVA_OPTS=""`, `USER recsys`, `EXPOSE 8010`, and the
`ENTRYPOINT` line are unchanged — the entrypoint `java` now resolves to `/opt/jre/bin/java`
via PATH.

- [ ] **Step 4: Build the image (test)**

```bash
docker buildx build -t recsys:phase3 .
```

Expected: build succeeds, including `jlink`, the `/opt/jre/bin/java -version` self-check, and the `test -s /app/app.jsa` guard.

- [ ] **Step 5: GATE — confirm ONNX now LOADS in model-serving**

This is the decisive verification: on the old alpine base this crashed with `UnsatisfiedLinkError`. It must now succeed.

```bash
docker rm -f p3onnx 2>/dev/null
docker run -d --name p3onnx -e RECSYS_MAIN_CLASS=com.recsys.api.rest.ModelApplication recsys:phase3
sleep 18
docker inspect -f '{{.State.Status}} exit={{.State.ExitCode}}' p3onnx
docker logs p3onnx 2>&1 | grep -iE "UnsatisfiedLink|onnx|Pre-warming|Started ModelApplication|Tomcat started|Error starting" | head -20
docker rm -f p3onnx
```

Expected: status `running` (NOT exited), NO `UnsatisfiedLinkError`, the `Pre-warming model runtime` line is followed by a successful `Started ModelApplication` (no ONNX crash). If a `jdeps`-missed module surfaces as `NoClassDefFound`/missing-module, add it to the `MODS` safety set in Step 1 and rebuild.

- [ ] **Step 6: Smoke-start the other three services**

```bash
for MC in com.recsys.api.gateway.MicroserviceGatewayServer \
          com.recsys.api.serving.RecSysServer \
          com.recsys.api.online.OnlinePredictionServer; do
  echo "== $MC =="; \
  docker rm -f p3smoke 2>/dev/null; \
  docker run -d --name p3smoke -e RECSYS_MAIN_CLASS=$MC recsys:phase3; \
  sleep 8; \
  docker logs p3smoke 2>&1 | grep -iE "UnsatisfiedLink|NoClassDefFound|ClassNotFound|Exception in thread|module" | head -10; \
  docker rm -f p3smoke; \
done
```

Expected: none logs a missing-module / native-load / entrypoint-class error (downstream Redis-connection errors are acceptable — unrelated to the JRE trim).

- [ ] **Step 7: Confirm the size win**

```bash
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep -E 'recsys:phase3'
docker history --no-trunc recsys:phase3 | head -5
```

Expected: `recsys:phase3` is materially smaller than the ~170 MB full-corretto image (target ~90–120 MB with the jlinked JRE on debian-slim).

- [ ] **Step 8: Commit (only if Steps 5–6 fully passed)**

```bash
git add Dockerfile
git commit -m "perf(docker): jlink glibc JRE on debian:12-slim; fixes ONNX, shrinks image

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

**If the ONNX gate (Step 5) fails** with a *missing JDK module* (`NoClassDefFound` for a `java.*`/`jdk.*` class), that is fixable — add the module to the `MODS` set and rebuild. If it fails for a non-module reason you cannot resolve (e.g. ONNX needs a glibc lib beyond `libstdc++6`/`libgomp1`), report BLOCKED with the exact error rather than committing — do not ship a model-serving image that still crashes.

---

## Self-Review

**Spec coverage:**
- Phase 1 (`.m2` cache mount, drop `go-offline`, fix main class, tighten `.dockerignore`) → Task 1. ✓
- Phase 2 (build-time AppCDS, keep alpine-corretto, single shared `.jsa`) → Task 2. ✓
- Phase 3 (jlink glibc JRE on `debian:12-slim`, ONNX positive gate, AppCDS regen) → Task 3 (revised). ✓
- Cross-cutting: deps-before-classes preserved (Task 1 Interfaces + Global Constraints); one commit per phase (each task's final Step); local-build verification (every task's build/smoke steps). ✓
- **Revision note:** the spec's original "stay on alpine" was invalidated by the Phase-2 discovery that ONNX requires glibc; Task 3 now targets `debian:12-slim` (glibc), which both delivers the slim win and fixes the pre-existing model-serving ONNX crash. jlink runs in the glibc `maven` build stage; the AppCDS archive is regenerated on the glibc JVM. ✓

**Placeholder scan:** no TBD/TODO/"handle edge cases"; all RUN/ENTRYPOINT/command blocks are concrete. ✓

**Type/path consistency:** `/workspace/app-classes.jar`, `/workspace/target/dependency`, `/opt/jre`, `/app/app-classes.jar`, `/app/dependency`, `/app/app.jsa`, and `com.recsys.api.gateway.MicroserviceGatewayServer` are used identically across tasks. (Note: Task 1's original `/app/classes` directory was eliminated in the Task-2 fix in favour of `/app/app-classes.jar`.) ✓
