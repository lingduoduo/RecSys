# ONNX Model Serving Hardening Design

## Objective

Harden the embedded ONNX recommendation service so that model artifacts are deployed consistently, incompatible models fail before readiness, experimental model failures degrade to control without corrupting A/B telemetry, overload behavior remains bounded, and performance tests prove that ONNX inference actually ran.

This change remains backward-compatible with existing model bundles. A bundle without a manifest continues to load through the current locator and emits one warning per variant. A bundle containing a manifest is validated strictly.

## Current Architecture

The Spring Boot model service owns one `OrtSession` per loaded A/B variant. `ModelRuntimeProvider` loads feature metadata and embeddings, creates `UserTowerInferenceService`, and combines retrieval with `RankingStage`. Ranking sends all encodable in-vocabulary candidates through one batched ONNX invocation; out-of-vocabulary candidates retain their recall score below the ONNX tier.

The service currently prewarms all configured variants, marks a session ready after `createSession`, and identifies models through the global `recsys.model.file` property. External artifact files are opened independently from a mutable directory. Recall channels run on a fixed thread pool with an unbounded queue, while request admission permits substantially more concurrency than the Kubernetes CPU limit can execute predictably.

## Scope

The implementation covers all findings from the 2026-09-03 repository investigation:

1. Correct served-variant attribution after A/B fallback.
2. Allow failed experimental prewarming to degrade to control while keeping control failure fatal.
3. Validate the ONNX input/output contract and execute a smoke inference before readiness.
4. Add an optional checksummed artifact manifest and document immutable publication.
5. Honor the manifest model filename instead of relying only on a global filename.
6. Bound recall submission, cancel timed-out work, and expose rejection/timeout metrics.
7. Configure ONNX execution concurrency explicitly for CPU-limited pods.
8. Make Java and k6 load tests demonstrate real ONNX execution rather than cache throughput.

The change also adds operational metrics, alerts, Kubernetes defaults, and corrected artifact documentation needed to operate these behaviors.

## Non-Goals

- Introducing Triton, ONNX Runtime Server, or another remote inference service.
- Building a model registry or object-store download subsystem.
- Creating a new training/export pipeline.
- Changing the DSSM architecture, recommendation API response schema, or ranking policy.
- Upgrading Java, Spring Boot, or ONNX Runtime dependencies.
- Making manifests mandatory for legacy bundles in this release.

## Artifact Manifest

Each variant may contain `model_manifest.json` alongside `feature_config.json`. Its version-one schema is:

```json
{
  "schema_version": 1,
  "model_version": "dssm-2026-09-03",
  "model_file": "dssm_model.onnx",
  "sha256": {
    "feature_config.json": "<64 lowercase hex characters>",
    "dssm_model.onnx": "<64 lowercase hex characters>"
  },
  "inputs": {
    "user_id": { "type": "INT64", "rank": 1 },
    "item_id": { "type": "INT64", "rank": 1 }
  },
  "output": {
    "name": "score",
    "type": "FLOAT",
    "rank": 1
  }
}
```

`schema_version` must equal `1`. The manifest filename is fixed and not configurable. `model_file` must be a simple relative filename: absolute paths, parent traversal, and separators are rejected. The checksum map must cover `feature_config.json` and the selected model. Additional checksummed companion files are permitted and validated when present.

For a manifest-backed bundle, `model_version` must equal the feature config version and `model_file` is authoritative. The global `recsys.model.file` remains the legacy-bundle selector only. A manifest or checksum error rejects the variant; it never falls through to an unchecked root artifact.

To prevent mixed generations within one runtime build, the loader reads manifest-referenced files into an immutable in-memory `ModelArtifactSnapshot` once, verifies every checksum, then constructs metadata and the ONNX session from those bytes. Legacy loading retains the existing stream-based lookup for compatibility.

External production publication uses immutable generation directories. Producers fully write and verify `<artifact-root>/releases/<generation>/<variant>/`, then atomically switch the configured artifact-root symlink between pod deployments. In-place replacement of files inside the active generation is unsupported. The application does not watch or hot-reload directories.

## Model Contract and Readiness

`UserTowerInferenceService` receives validated model bytes and an expected contract. After creating its `OrtSession`, it inspects ONNX Runtime input and output metadata:

- `user_id` and `item_id` must exist, use `INT64`, and have rank one.
- The configured output must exist, use `FLOAT`, and have rank one.
- Unexpected additional inputs are rejected because serving cannot populate them.
- Additional outputs are allowed and ignored.

For legacy bundles, the expected names and types are the current defaults. For manifest bundles, the manifest contract is authoritative, but version one still requires the two serving input names because the Java feature adapter exposes those semantics.

The service then runs a one-row smoke inference using encoded unknown-user and unknown-item indices. The output must contain exactly one finite score. Only after metadata validation and smoke inference succeed does `isReady()` become true.

Initialization is transactional: partially constructed sessions and `SessionOptions` are closed on every failure. `close()` is idempotent, clears the session reference, and sets readiness false. Runtime inference validates that the returned output length equals the submitted batch and that every score is finite; contract violations fail the request and contribute to inference-failure metrics.

## Variant Lifecycle and Attribution

Startup first loads the normalized default/control variant synchronously. Its failure remains fatal. Enabled non-default variants warm concurrently; each failure is passed to `VariantRuntimeResolver` failure state, increments `recsys.model.runtime_load_failures{variant,phase="warmup"}`, and logs the control fallback, but does not abort application startup.

The resolver retains its single-attempt and cooldown behavior for later recovery. A successful retry clears failure state and begins serving the treatment without restarting the pod.

`OnnxInferencePipeline` writes the actual variant returned by `RecommendResponse.abTestVariant()` to `RecommendationResult.trace.abTestVariant`. `ProtectedRecommendationPipeline` compares it with the deterministic assignment to calculate `fellBack`, tag success metrics, and emit exposure events. The assigned variant remains available inside the exposure record; served results are never attributed to a model that did not run.

During overload, cache lookup uses the already-loaded assigned runtime when available. If it is absent or marked failed, the path uses the already-loaded control runtime and corresponding control cache key without triggering any model load. This mirrors normal resolution while preserving the rule that overload degradation performs no cold initialization.

## Bounded Recall Execution

Replace the unbounded fixed executor with a dedicated `ThreadPoolExecutor` configured through validated properties:

- `recsys.model.recall.core-threads`: default `2 * availableProcessors`, minimum `1`.
- `recsys.model.recall.queue-capacity`: default `256`, minimum `1`.
- `recsys.model.recall.timeout-ms`: default `200`, minimum `1`.

The executor uses a bounded `ArrayBlockingQueue` and an abort rejection policy. Each submitted channel retains its task handle. Timeout completes that channel as degraded and requests interruption through `cancel(true)`. Channel implementations must preserve thread interruption rather than swallowing it. Because some external clients may not immediately honor interruption, queue bounding remains the hard memory-safety guarantee.

Rejected and timed-out channels follow existing partial-result and fallback behavior. They increment:

- `recsys.model.recall.tasks{result="rejected",channel="..."}`
- `recsys.model.recall.tasks{result="timeout",channel="..."}`

Channel labels come from the closed configured channel set; no user-controlled metric labels are permitted.

## ONNX Execution Configuration

Add validated Spring properties:

- `recsys.model.onnx.intra-op-threads`: default `1`, minimum `1`.
- `recsys.model.onnx.inter-op-threads`: default `1`, minimum `1`.
- `recsys.model.onnx.execution-mode`: default `SEQUENTIAL`, allowed values `SEQUENTIAL` and `PARALLEL`.

`UserTowerInferenceService` applies these values to `OrtSession.SessionOptions`. Kubernetes sets the same conservative values explicitly. Request admission defaults to `8` concurrent requests per two-CPU model pod instead of `64`; it remains configurable. These are safety defaults, not capacity claims, and future changes require measurements from the corrected inference load test.

Every call to `OrtSession.run` increments `recsys.model.onnx.runs{variant}`. Success and failure remain captured by the existing request-level inference metrics. Variant labels use the normalized configured set and retain the existing cardinality cap.

## Deployment and Observability

Kubernetes configuration will:

- Set ONNX intra-op/inter-op threads to `1` and execution mode to `SEQUENTIAL`.
- Set model request concurrency to `8`.
- Override readiness failure rate to `0.05` and average latency to `500ms`.
- Preserve the existing CPU and memory resources in this PR; memory changes require measured native-memory evidence.

Prometheus rules will alert on:

- No ready model-serving replicas for five minutes.
- Sustained model request rejection above five percent for ten minutes with a minimum request-rate guard.
- P95 model inference latency above 500ms for ten minutes.
- Runtime-load failures increasing during a fifteen-minute window.

Rule tests must cover firing and non-firing boundary cases. Existing ServiceMonitor discovery remains unchanged.

## Load Verification

`InferenceLoadTest` will disable recommendation and cold-start caching, select known users/items, warm the runtime once, reset the ONNX run counter, and issue concurrent unique requests. It must assert:

- Every request succeeds.
- The observed ONNX run count is at least the number of uncached recommendation computations.
- Latency statistics are reported as characterization data, not hard universal capacity thresholds.

The k6 script will use known user IDs by default and vary exclusions so response caches cannot satisfy the measured path. Its setup and teardown logic will read the `recsys_model_onnx_runs_total` Prometheus counter from `/actuator/prometheus`; teardown fails the ONNX scenario unless the counter increased. Cache behavior remains a separate scenario rather than contaminating inference capacity measurements.

## Testing Strategy

All production changes use test-driven development. Focused coverage includes:

- Assigned treatment versus served control trace, metric tags, and exposure fields.
- Non-fatal experimental warmup failure, fatal control failure, cooldown, and recovery.
- Control-cache overload fallback without cold runtime construction.
- Manifest parsing, unsupported versions, unsafe paths, missing checksums, checksum mismatch, config-version mismatch, and legacy compatibility warning deduplication.
- ONNX input/output name, dtype, and rank validation; unexpected inputs; smoke-inference failure; batch-size mismatch; non-finite output; and idempotent close.
- Recall queue saturation, rejection degradation, timeout cancellation, interruption preservation, and executor shutdown.
- Configuration validation and translation into `SessionOptions`.
- Load tests that fail when ONNX execution is bypassed by caches.
- Prometheus rule firing and boundary behavior.

Focused Maven tests run after each task. Final verification runs the full default Maven suite, the corrected tagged load characterization, Kubernetes/Kustomize validation already used by the repository, Prometheus rule tests, and a container startup smoke test when the repository's Docker test environment is available.

## Compatibility and Rollout

Legacy bundles continue loading with their global model filename and emit a warning identifying the variant and resolved location. Manifest-backed bundles are strict and do not silently downgrade to legacy behavior when their manifest is malformed.

Recommended rollout order:

1. Deploy the compatible reader and operational defaults.
2. Publish manifest-backed immutable bundles and canary them as an experimental variant.
3. Confirm runtime-load, fallback, ONNX-run, latency, and rejection metrics.
4. Promote the validated generation to control.
5. Make manifests mandatory only in a later explicitly breaking change.

Rollback switches deployments to the prior immutable generation and application image. No cache or database migration is required.

## Acceptance Criteria

- A malformed or incompatible treatment model never makes the pod unavailable when control is healthy.
- A malformed or incompatible control model prevents readiness/startup.
- Responses, metrics, and exposure events identify the model variant that actually served the result.
- Manifest-backed runtimes cannot combine files whose checksums do not match one manifest.
- Legacy model bundles continue to serve with a warning.
- Recall submission has a hard queue bound and timed-out task cancellation is attempted.
- ONNX native parallelism and request concurrency are explicit and conservatively configured.
- Load tests fail if they exercise only caches or fallback ranking without an ONNX call.
- Production alerts cover model absence, rejection, latency, and runtime-load failure.
- Documentation accurately describes tracked demo assets, external bundle requirements, manifest schema, and immutable rollout.
