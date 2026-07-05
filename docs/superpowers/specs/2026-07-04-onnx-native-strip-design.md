# ONNX Native-Lib Strip — Design

**Date:** 2026-07-04
**Status:** Approved (pending spec review)
**Component:** Container image build (`Dockerfile`)

## Problem

`onnxruntime-1.18.0.jar` is **89 MB** — the single largest artifact in the shared image's
dependency layer, carried by **all four** services even though only `model-serving`
(`com.recsys.api.rest.ModelApplication`) ever loads the ONNX native library. The jar bundles
natives for five platforms, of which only the Linux `.so` files are ever used in the container:

| Native entry | Size | Used in container? |
|---|---|---|
| `native/osx-x64/libonnxruntime.dylib` (+ dSYM 8.7 MB) | 27.9 MB | no |
| `native/osx-aarch64/libonnxruntime.dylib` (+ dSYM 8.5 MB) | 24.9 MB | no |
| `native/win-x64/onnxruntime.dll` | 10.8 MB | no |
| `native/linux-x64/libonnxruntime.so` | 15.3 MB | yes (x64 nodes) |
| `native/linux-aarch64/libonnxruntime.so` | 12.7 MB | yes (Graviton nodes) |

The prior `docker-image-optimization` work slimmed the *model file* (40 KB, negligible) but never
touched the *runtime library* natives. Natives are near-incompressible, so ~81 MB of the 89 MB jar
is dead weight shipped to every service.

## Goal

Strip the non-Linux ONNX natives from the container image so every service's image (and the shared
dependency layer pulled on cold nodes / autoscale) is ~81 MB smaller, with no loss of function and
no CPU-arch coupling.

## Non-Goals

- No single-arch reduction — we keep **both** `linux-x64` and `linux-aarch64` so the image runs on
  x64 or Graviton without knowing the node arch (the extra ~13 MB is cheap insurance vs. a
  crash-loop from an arch mismatch). Decided during brainstorming.
- No Maven-level stripping — the dev machine is macOS and `mvn test` there loads the macOS native
  for ONNX-backed tests; stripping at the Maven level would break local testing. The strip MUST be
  container-only.
- No per-service image split, no ONNX version bump, no changes to k8s manifests, JVM flags, jlink,
  or AppCDS.

## Approach (chosen)

Strip in the **Dockerfile build stage** using `zip -d`, after `dependency:copy-dependencies`
populates `target/dependency/` and before the runtime `COPY`. Version-agnostic: globs the jar name
and deletes whole non-Linux platform directories. Rejected alternatives: Maven repackaging plugin
(breaks macOS dev tests — see Non-Goals); a separate slim ONNX artifact / build arg (overkill).

## The Change (`Dockerfile`, build stage)

1. Add `zip` to the existing tools install (currently `RUN yum install -y binutils` at line 15) →
   `yum install -y binutils zip` (no extra layer).
2. Add a `RUN` step (after dependencies are resolved, before the runtime `COPY --from=build
   /workspace/target/dependency`) that deletes the non-Linux natives in place:

```dockerfile
# Strip ONNX Runtime natives for platforms this image never runs on (macOS + Windows + dSYM
# debug symbols), keeping only the Linux .so files. ~81 MB off the shared dependency layer that
# all four services carry. Both linux arches kept so the image runs on x64 or Graviton.
RUN zip -d /workspace/target/dependency/onnxruntime-*.jar \
      'ai/onnxruntime/native/osx-x64/*' \
      'ai/onnxruntime/native/osx-aarch64/*' \
      'ai/onnxruntime/native/win-x64/*'
```

Only `native/` entries are removed; all `ai/onnxruntime/*.class` files are untouched.

### Why it is safe

- ONNX extracts the `.so` matching `os.arch` at runtime; both Linux arches remain, so x64 and
  Graviton both work.
- The jar is unsigned and we do not verify jar signatures, so removing entries is benign.
- jlink (module analysis) and AppCDS (class list/dump) operate on classes/modules, not natives —
  unaffected.
- `zip -d` exits non-zero if the pattern matches nothing, which also guards against a future jar
  layout change silently no-op'ing the strip (the build fails loudly instead).

## Verification (image-level; the acceptance gate)

The image build runs `mvn -DskipTests`, and the Maven test suite uses the unstripped `~/.m2` jar,
so the Java suite is unaffected and out of scope. Acceptance is image-level, runnable locally
(Docker daemon confirmed available):

1. **Build:** `DOCKER_BUILDKIT=1 docker build -t recsys:onnx-strip .` → success. Also build current
   `main` as `recsys:onnx-base` for the size delta.
2. **Strip worked:** copy the jar out (`docker create` + `docker cp
   /app/dependency/onnxruntime-*.jar`) and `unzip -l` on the host — assert `linux-x64` and
   `linux-aarch64` natives present, `osx-*` and `win-*` **absent**, and jar size ~89 MB → ~28 MB.
3. **ONNX still loads (critical):**
   `docker run --rm -e RECSYS_MAIN_CLASS=com.recsys.api.rest.ModelApplication -p 8080:8080
   recsys:onnx-strip` → clean startup ("Started ModelApplication", ONNX warmup succeeds),
   `/health/ready` → 200, **no `UnsatisfiedLinkError`** in logs. Model-serving warms ONNX at boot,
   so a clean ready is proof the native loaded.
4. **Non-model smoke:** boot the gateway (default main class) → starts cleanly with the slimmed jar
   on the classpath (a no-op for the three ONNX-free services).
5. **Size delta:** `docker images` for base vs stripped → report MB saved off the image.

**Arch caveat:** local `docker build` on this Mac produces a `linux/arm64` image, so step 3
exercises the **aarch64** native directly. The **x64** native is verified by presence (step 2); a
full x64 boot would require `--platform linux/amd64` under emulation — optional, since we only
retain the unmodified upstream x64 `.so`.

## Files Touched

- Modify: `Dockerfile` (build stage — add `zip` to the tools install; add the `zip -d` strip step).

No other files. No Java changes, no test files, no k8s changes.
