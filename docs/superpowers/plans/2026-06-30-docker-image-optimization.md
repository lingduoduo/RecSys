# Docker Image Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut CI/local build time and pod pull+startup time for the single shared image backing all four services, landing safe wins first and gating the riskier runtime slimming behind real verification.

**Architecture:** Three independent, individually-revertable changes to `Dockerfile` + `.dockerignore`. Phase 1 speeds builds (BuildKit `.m2` cache mount) and fixes the stale default main class. Phase 2 adds a build-time AppCDS archive for faster JVM startup, keeping the alpine-corretto runtime base. Phase 3 replaces the runtime JRE with a `jlink`-trimmed musl JRE on a slim alpine base, verified against the ONNX service before it ships.

**Tech Stack:** Docker (BuildKit, default in Docker 29.x), multi-stage builds, Maven 3.9 / Amazon Corretto 17, AppCDS (dynamic CDS), `jdeps`/`jlink`.

## Global Constraints

- Edit only `Dockerfile` and `.dockerignore`. No application-code changes. No CI changes (repo has none).
- Java 17 (Amazon Corretto). Build stage `maven:3.9-amazoncorretto-17` (glibc). Any jlink step runs in an alpine-corretto (musl) stage so the trimmed JRE matches the musl alpine final base — never jlink glibc→musl.
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

### Task 3: Phase 3 — jlink-trimmed musl JRE on a slim alpine base (ONNX-gated)

**Files:**
- Modify: `Dockerfile` (add a musl jlink builder stage; replace the runtime base + JRE)

**Interfaces:**
- Consumes: `/workspace/target/classes`, `/workspace/target/dependency`, `/workspace/app.jsa` from prior stages.
- Produces: a runtime image based on `alpine:3.20` carrying a custom `/opt/jre` JRE; the entrypoint invokes `/opt/jre/bin/java`.

- [ ] **Step 1: Add a musl jlink builder stage**

Insert after the existing `FROM maven:... AS build` stage (and before the runtime `FROM`):

```dockerfile
# Build a minimal musl JRE with jlink. amazoncorretto:17-alpine is a full musl JDK,
# so the resulting JRE is musl-native and runs on an alpine final base.
FROM amazoncorretto:17-alpine AS jre
WORKDIR /workspace
COPY --from=build /workspace/target/classes ./classes
COPY --from=build /workspace/target/dependency ./dependency
# Detect required modules from the actual classpath, then union with a safety set for
# modules pulled in reflectively (crypto, naming/JNDI, JDBC, management, instrumentation)
# that jdeps cannot see — ONNX Runtime and Spring both rely on these.
RUN set -eux; \
    MODS="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 17 \
              -cp 'dependency/*' --recursive classes 2>/dev/null || echo java.base)"; \
    MODS="${MODS},jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,java.naming,java.management,java.sql,jdk.zipfs,java.security.jgss,java.instrument,jdk.jfr"; \
    jlink --add-modules "$MODS" --strip-debug --no-man-pages --no-header-files \
          --compress=2 --output /opt/jre; \
    /opt/jre/bin/java -version
```

- [ ] **Step 2: Replace the runtime stage base and JRE**

Change the runtime stage from `FROM amazoncorretto:17-alpine` to a slim alpine that gets the jlinked JRE. Replace the runtime `FROM` line and add the JRE copy + PATH:

```dockerfile
FROM alpine:3.20
WORKDIR /app

# musl runtime libs needed by the JRE and ONNX native .so files
RUN apk add --no-cache libstdc++ libgcc \
    && addgroup -S recsys && adduser -S -G recsys recsys
COPY --from=jre /opt/jre /opt/jre
ENV PATH="/opt/jre/bin:${PATH}"
```

Keep the existing `COPY --from=build ... classes`, `COPY --from=build ... dependency`, `COPY --from=build /workspace/app.jsa /app/app.jsa`, the `ENV RECSYS_MAIN_CLASS=...`, `ENV JAVA_OPTS=""`, `USER recsys`, `EXPOSE 8010`, and the `ENTRYPOINT` lines unchanged (the entrypoint's `java` now resolves to `/opt/jre/bin/java` via PATH). Remove the old `RUN addgroup ... adduser ...` line from the runtime stage since user creation now lives in the `apk add` RUN above.

- [ ] **Step 3: Build the image (test)**

Run:

```bash
docker build -t recsys:phase3 .
```

Expected: build succeeds, including `jlink` and the `/opt/jre/bin/java -version` self-check.

- [ ] **Step 4: GATE — start the ONNX model-serving service and exercise it**

This is the decisive verification; ONNX Runtime loads native `.so`s and uses service-loading that `jdeps` can miss.

```bash
docker run --rm -d --name recsys-p3-onnx -p 8080:8080 \
  -e RECSYS_MAIN_CLASS=com.recsys.api.rest.ModelApplication recsys:phase3
sleep 15
docker logs recsys-p3-onnx 2>&1 | grep -iE "onnx|UnsatisfiedLink|NoClassDefFound|ClassNotFound|Exception in thread|started|8080" | head -30
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health || true
docker rm -f recsys-p3-onnx
```

Expected: model-serving starts, NO `UnsatisfiedLinkError` / missing-module / ONNX native-load failure, and the health endpoint responds (or the service is clearly up in logs).

- [ ] **Step 5: Smoke-start the other three services**

```bash
for MC in com.recsys.api.gateway.MicroserviceGatewayServer \
          com.recsys.api.serving.RecSysServer \
          com.recsys.api.online.OnlinePredictionServer; do
  echo "== $MC =="; \
  docker run --rm -d --name p3smoke -e RECSYS_MAIN_CLASS=$MC recsys:phase3; \
  sleep 8; \
  docker logs p3smoke 2>&1 | grep -iE "UnsatisfiedLink|NoClassDefFound|ClassNotFound|Exception in thread" | head -10; \
  docker rm -f p3smoke; \
done
```

Expected: none of the three logs a missing-module / native-load / entrypoint-class error (downstream Redis-connection errors are acceptable — they are unrelated to the JRE trim).

- [ ] **Step 6: Confirm the size win**

```bash
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep -E 'recsys:(phase1|phase3)'
```

Expected: `recsys:phase3` is materially smaller than `recsys:phase1` (target ~70–100 MB vs ~170 MB).

- [ ] **Step 7: Commit (only if Steps 4–5 fully passed)**

```bash
git add Dockerfile
git commit -m "perf(docker): ship a jlink-trimmed musl JRE on slim alpine base

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

**Fallback (mandatory if the ONNX gate fails):** if Step 4 shows any `UnsatisfiedLinkError`, missing module, or ONNX native-load failure that cannot be fixed by adding the named module to the `MODS` safety set in Step 1, revert this task (`git checkout Dockerfile`) and stop at the Phase-2 conservative image. Do NOT ship a half-broken runtime.

---

## Self-Review

**Spec coverage:**
- Phase 1 (`.m2` cache mount, drop `go-offline`, fix main class, tighten `.dockerignore`) → Task 1. ✓
- Phase 2 (build-time AppCDS, keep alpine-corretto, single shared `.jsa`) → Task 2. ✓
- Phase 3 (jlink slim JRE, ONNX gate + fallback, alpine base) → Task 3. ✓
- Cross-cutting: deps-before-classes preserved (Task 1 Interfaces + Global Constraints); one commit per phase (each task's final Step); local-build verification (every task's build/smoke steps). ✓
- Spec's "stay on alpine" → Task 3 keeps `alpine:3.20`; the plan adds the musl-consistency correction (jlink in alpine-corretto stage) discovered after the spec, which is a faithful refinement, not a scope change. ✓

**Placeholder scan:** no TBD/TODO/"handle edge cases"; all RUN/ENTRYPOINT/command blocks are concrete. ✓

**Type/path consistency:** `/workspace/target/classes`, `/workspace/target/dependency`, `/workspace/app.jsa`, `/app/app.jsa`, `/opt/jre`, and `com.recsys.api.gateway.MicroserviceGatewayServer` are used identically across tasks. ✓
