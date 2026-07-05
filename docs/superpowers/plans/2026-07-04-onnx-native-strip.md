# ONNX Native-Lib Strip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strip the non-Linux ONNX Runtime native libs from the container image so every service's image (and the shared dependency layer) is ~81 MB smaller, with no loss of function and no CPU-arch coupling.

**Architecture:** A single `Dockerfile` build-stage change: after `dependency:copy-dependencies` resolves the jars, delete the macOS + Windows + dSYM native entries from `onnxruntime-*.jar` in place with `zip -d`, keeping both Linux `.so` files. Verification is image-level (build + boot model-serving), since this is a container change and the Maven suite uses the unstripped local jar.

**Tech Stack:** Docker (BuildKit), `maven:3.9-amazoncorretto-17` build stage (Amazon Linux 2, `yum`), `debian:12-slim` runtime, `zip`, ONNX Runtime 1.18.0.

## Global Constraints

- Change is **container-only** — do NOT strip at the Maven level (the dev machine is macOS and `mvn test` there loads the macOS native; Maven-level stripping breaks local testing).
- Keep **both** `linux-x64` and `linux-aarch64` natives (arch-agnostic; no node-arch assumption). Strip only `osx-x64`, `osx-aarch64`, `win-x64` (which also removes the dSYM debug trees under the osx dirs).
- Only `native/` entries are removed; all `ai/onnxruntime/*.class` files stay.
- Only file touched: `Dockerfile`. No Java, no test files, no k8s changes.
- Build with BuildKit: `DOCKER_BUILDKIT=1 docker build ...`.
- Image builds are slow (Maven + jlink + AppCDS training). Use a long timeout (≥600 s) or run the build in the background.
- Commit only the `Dockerfile` change.
- Work stays on branch `feat/onnx-native-strip` (already created). Do not merge to main.

## File Structure

- **Modify** `Dockerfile` — build stage: add `zip` to the tools install; add a `zip -d` strip step after dependencies are resolved.

---

### Task 1: Strip non-Linux ONNX natives from the image

**Files:**
- Modify: `Dockerfile` (build stage — the `yum install` line ~15 and a new `RUN` before the runtime `COPY --from=build /workspace/target/dependency`)

**Interfaces:**
- Consumes: nothing (self-contained Dockerfile change).
- Produces: a container image whose `/app/dependency/onnxruntime-*.jar` contains only `linux-x64` and `linux-aarch64` natives.

This is a Dockerfile change, so verification is image-level rather than a Java unit test. The RED/GREEN framing: the **baseline** build proves the macOS/Windows natives are present (the bloat exists); the **stripped** build proves they are gone AND model-serving still loads ONNX.

- [ ] **Step 1 (RED — baseline): build current HEAD and confirm the bloat is present**

Run (long-running; allow ≥600 s or background it):
```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
DOCKER_BUILDKIT=1 docker build -t recsys:onnx-base .
```
Expected: `BUILD SUCCESS` / image built.

Then confirm the unstripped jar carries the non-Linux natives (RED — the thing we will remove is present). Copy the jar out of the image and list it:
```bash
id=$(docker create recsys:onnx-base)
docker cp "$id":/app/dependency/. /tmp/onnx-base-deps >/dev/null
docker rm "$id" >/dev/null
BASEJAR=$(ls /tmp/onnx-base-deps/onnxruntime-*.jar)
echo "baseline jar: $(du -h "$BASEJAR" | cut -f1)"
unzip -l "$BASEJAR" | grep -E 'native/(osx|win)' | head
```
Expected: jar is ~89 MB (`du` shows ~85M–89M); the `grep` prints macOS/Windows native entries (`osx-x64`, `osx-aarch64`, `win-x64`) — confirming the bloat is present before the change.

Also record the baseline image size:
```bash
docker images recsys:onnx-base --format '{{.Size}}'
```
Record the value for the size-delta report.

- [ ] **Step 2 (GREEN — implement): edit the Dockerfile**

Insert exactly one new `RUN` immediately after the `jar cf ... app-classes.jar` line (line 8), so it runs after `dependency:copy-dependencies` has populated `/workspace/target/dependency`. It self-installs `zip` (this step precedes the line-15 `binutils` install, so it cannot rely on it) and strips the natives:
```dockerfile
# Strip ONNX Runtime natives for platforms this image never runs on (macOS + Windows, incl.
# their dSYM debug symbols), keeping only the Linux .so files. ~81 MB off the shared dependency
# layer that all four services carry. Both linux arches kept so the image runs on x64 or Graviton.
# zip -d exits non-zero if nothing matches, so a future jar-layout change fails the build loudly.
RUN yum install -y zip >/dev/null 2>&1 \
    && zip -d /workspace/target/dependency/onnxruntime-*.jar \
         'ai/onnxruntime/native/osx-x64/*' \
         'ai/onnxruntime/native/osx-aarch64/*' \
         'ai/onnxruntime/native/win-x64/*'
```

Do NOT modify the existing line-15 `yum install -y binutils` — leave it as-is. This is the only edit: one added `RUN`.

- [ ] **Step 3 (GREEN — build the stripped image)**

Run (long-running; allow ≥600 s or background it):
```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
DOCKER_BUILDKIT=1 docker build -t recsys:onnx-strip .
```
Expected: `BUILD SUCCESS`. (If `zip -d` matched nothing it would fail here — a passing build means the entries were found and removed.)

- [ ] **Step 4 (GREEN — assert the strip worked)**

```bash
id=$(docker create recsys:onnx-strip)
docker cp "$id":/app/dependency/. /tmp/onnx-strip-deps >/dev/null
docker rm "$id" >/dev/null
STRIPJAR=$(ls /tmp/onnx-strip-deps/onnxruntime-*.jar)
echo "stripped jar: $(du -h "$STRIPJAR" | cut -f1)"
echo "--- linux natives (must be present) ---"
unzip -l "$STRIPJAR" | grep -E 'native/linux'
echo "--- osx/win natives (must be ABSENT) ---"
unzip -l "$STRIPJAR" | grep -E 'native/(osx|win)' && echo "FAIL: non-linux natives remain" || echo "OK: no osx/win natives"
echo "--- onnxruntime classes still present ---"
unzip -l "$STRIPJAR" | grep -cE 'ai/onnxruntime/.*\.class'
```
Expected:
- stripped jar ~28 MB (down from ~89 MB).
- `linux-x64/libonnxruntime.so`, `linux-aarch64/libonnxruntime.so` (and both `*4j_jni.so`) listed.
- the osx/win grep prints nothing → `OK: no osx/win natives`.
- class count > 0 (classes untouched).

- [ ] **Step 5 (GREEN — critical: model-serving still loads ONNX)**

Boot model-serving from the stripped image and confirm the native loads (no `UnsatisfiedLinkError`). Local `docker build` on this Mac yields a `linux/arm64` image, so this exercises the aarch64 native:
```bash
docker rm -f onnx-verify 2>/dev/null || true
docker run -d --name onnx-verify \
  -e RECSYS_MAIN_CLASS=com.recsys.api.rest.ModelApplication \
  -p 18080:8080 recsys:onnx-strip
# give Spring Boot + ONNX warmup time to start
sleep 35
echo "=== readiness ==="
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18080/health/ready || true
echo "=== ONNX / startup log signals ==="
docker logs onnx-verify 2>&1 | grep -iE 'Started ModelApplication|onnx|UnsatisfiedLinkError|native|Pre-warm' | tail -20
docker rm -f onnx-verify >/dev/null
```
Expected (acceptance gate):
- **No `UnsatisfiedLinkError`** anywhere in the logs (this is the hard native-load gate).
- Logs show ONNX warmup / `Started ModelApplication`.
- `/health/ready` returns `200` (model-serving readiness gates on the model being loaded, which requires the native — a 200 corroborates the native loaded). If it is not yet 200 after 35 s, increase the sleep to 60 s and re-check; only treat a genuine `UnsatisfiedLinkError` or a crash as a failure.

If an `UnsatisfiedLinkError` appears, STOP — the strip removed a needed entry (report it; do not proceed to commit).

- [ ] **Step 6 (GREEN — smoke: non-model service still starts)**

Confirm the three ONNX-free services still start with the slimmed jar on the classpath (default main class = gateway):
```bash
docker rm -f gw-verify 2>/dev/null || true
docker run -d --name gw-verify -p 18010:8010 recsys:onnx-strip   # default RECSYS_MAIN_CLASS = gateway
sleep 12
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18010/health || true
docker logs gw-verify 2>&1 | grep -iE 'Starting RecSys API gateway|UnsatisfiedLinkError|Exception' | tail -10
docker rm -f gw-verify >/dev/null
```
Expected: gateway logs `Starting RecSys API gateway on port 8010`, `/health` reachable, no exceptions.

- [ ] **Step 7 (size-delta report)**

```bash
echo "base  image: $(docker images recsys:onnx-base   --format '{{.Size}}')"
echo "strip image: $(docker images recsys:onnx-strip  --format '{{.Size}}')"
echo "onnx jar: base $(du -h /tmp/onnx-base-deps/onnxruntime-*.jar | cut -f1) -> strip $(du -h /tmp/onnx-strip-deps/onnxruntime-*.jar | cut -f1)"
```
Record the deltas in the task report (expected: image ~81 MB smaller; jar ~89 MB → ~28 MB).

Cleanup (optional): `rm -rf /tmp/onnx-base-deps /tmp/onnx-strip-deps; docker image rm recsys:onnx-base recsys:onnx-strip 2>/dev/null || true`.

- [ ] **Step 8: Commit**

```bash
git add Dockerfile
git commit -m "perf(docker): strip non-Linux ONNX natives from image (~81MB smaller)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Strip macOS + Windows + dSYM, keep both Linux arches → Step 2b (`zip -d` osx-x64/osx-aarch64/win-x64). ✓
- Container-only (not Maven) → the change lives solely in the Dockerfile build stage; Global Constraints call this out. ✓
- Version-agnostic jar glob → `onnxruntime-*.jar`. ✓
- Only `native/` entries removed, classes kept → Step 4 asserts class count > 0. ✓
- Build loudly fails if nothing matches → `zip -d` non-zero exit noted in Step 2b + Step 3. ✓
- Verification: build, strip-worked assertion, ONNX boot, non-model smoke, size delta → Steps 1,3,4,5,6,7. ✓
- Only `Dockerfile` touched → Step 8 commits only `Dockerfile`. ✓
- Arch caveat (local build = arm64; x64 verified by presence) → Step 5 note + Step 4 presence check. ✓

**Placeholder scan:** No TBD/TODO; every step has exact commands and expected output. ✓

**Consistency:** Image tags `recsys:onnx-base` / `recsys:onnx-strip`, temp dirs `/tmp/onnx-base-deps` / `/tmp/onnx-strip-deps`, and the jar glob `onnxruntime-*.jar` are used consistently across steps. ✓

**Risk notes (verify at implementation):**
- The strip `RUN` is inserted at ~line 9 (before the line-15 `yum install`), so it MUST self-install `zip` — Step 2 keeps `yum install -y zip` inside that RUN for this reason, and leaves line 15 untouched.
- Model-serving may need >35 s to warm ONNX on a cold container; Step 5 says bump the sleep before treating non-200 as failure — only a real `UnsatisfiedLinkError`/crash is a hard fail.
- If the Docker build exceeds the shell timeout, run it with a ≥600 s timeout or in the background and poll.
