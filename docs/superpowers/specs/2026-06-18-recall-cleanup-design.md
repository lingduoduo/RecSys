# Recall Cleanup Design (Sub-project 3 of 3)

_Date: 2026-06-18_
_Scope: Retire the now-dead `OnlineRecommendationEngine`; make the recall channel timeout env-tunable on both ports via the shared `RecallConfig` builder._
_Depends on: sub-project 2 (PR #126, branch `feat/online-recall-adoption`) — which removed the engine's construction from `OnlinePredictionServer`. This work stacks on that branch until #126 merges._

---

## 1. Problem Statement

Sub-projects 1 and 2 already converged both serving ports onto the shared `MultiChannelRecallService` + `RecallConfig`. Two loose ends remain:

1. **Dead code:** `OnlineRecommendationEngine` (`online/serving/`) has zero `src/main` callers after sub-project 2 removed its construction from `OnlinePredictionServer`. Its recent-history-similarity logic was reimplemented as `OnlineRecentHistoryChannel`. Only the class and its test remain.
2. **Hardcoded knob:** the recall channel timeout is `200 ms`, hardcoded in `RecSysServer` (a `DEFAULT_CHANNEL_TIMEOUT_MS` constant) and `OnlinePredictionServer` (a literal `200L`). There is no single tunable knob.

There is **no shared "registry"** to build — the two ports have different channel sets and quotas (`defaultMovie()` vs `defaultOnline()`), so a forced shared registry would be a leaky abstraction. Convergence is already achieved through the shared service + builder; this sub-project is cleanup, not new abstraction.

---

## 2. Chosen Approach

- Delete `OnlineRecommendationEngine` and its test.
- Make `RecallConfig.Builder`'s default `channelTimeoutMs` read `RECALL_CHANNEL_TIMEOUT_MS` (default `200L`), so a single env var tunes both ports. Both ports stop passing `.channelTimeoutMs(...)` explicitly and inherit the env-tunable default.

**Invariant:** with `RECALL_CHANNEL_TIMEOUT_MS` unset, both ports keep their current 200 ms timeout — no behavior change.

---

## 3. Components

### 3.1 Retire `OnlineRecommendationEngine`

- Delete `src/main/java/com/recsys/online/serving/OnlineRecommendationEngine.java` (class + its nested `OnlineRecommendationResult` record).
- Delete `src/test/java/com/recsys/online/serving/OnlineRecommendationEngineTest.java`.
- Confirmed via grep: no other `src/main` reference exists. The top-level `OnlineRecommendationResult` (returned by `OnlineRecommendationService`) is a separate type and stays.

### 3.2 Env-tunable channel timeout in `RecallConfig.Builder`

`RecallConfig.java` (`service/retrieval/multichannel/`):

- Add a package-private `static long readLongEnv(String name, long defaultValue)` (parse `System.getenv`, fall back to default on null/blank/`NumberFormatException`) — same shape as `ShardedTopKStore.readLongEnv`.
- The `Builder`'s `channelTimeoutMs` field default becomes `readLongEnv("RECALL_CHANNEL_TIMEOUT_MS", 200L)` instead of the literal `200L`. (The builder's `channelTimeoutMs(long)` setter and `build()`'s `>= 1` validation are unchanged, so an explicit caller override still works.)

### 3.3 `RecSysServer` (`serving/RecSysServer.java`)

- Remove the `.channelTimeoutMs(DEFAULT_CHANNEL_TIMEOUT_MS)` call from the `RecallConfig.builder()` chain (it inherits the env-tunable default).
- Remove the now-unused `DEFAULT_CHANNEL_TIMEOUT_MS` constant.

### 3.4 `OnlinePredictionServer` (`online/serving/OnlinePredictionServer.java`)

- Remove the `.channelTimeoutMs(200L)` call from the `RecallConfig.builder()` chain.

`MultiChannelRecallService`'s own `DEFAULT_CHANNEL_TIMEOUT_MS = 200L` (used only by its test-convenience 1-arg/5-arg constructors) is left unchanged — it is not on the production path.

---

## 4. Behavior

No behavior change when `RECALL_CHANNEL_TIMEOUT_MS` is unset: `RecallConfig.Builder` defaults to 200 ms, identical to today's hardcoded values on both ports. Operators set `RECALL_CHANNEL_TIMEOUT_MS=<ms>` to tune both ports' per-channel recall timeout from one place.

---

## 5. Testing Strategy

- **`RecallConfigTest` (extend):** the builder's default `channelTimeoutMs` is `200L` when the env var is unset (assert via `RecallConfig.builder().channels(...).executor(...).build().channelTimeoutMs()`); a name-contract test of `RecallConfig.readLongEnv("RECALL_CHANNEL_TIMEOUT_MS", <default>)` returning the supplied default when unset (the runtime override is documented, not unit-asserted, because Java env vars are immutable at runtime — same limitation as `ShardedTopKStoreTtlConfigTest`).
  - _Follow-up (2026-06-19 reconciliation):_ the parse/trim/fallback logic was extracted behind a pure package-private `RecallConfig.parseLongOrDefault(String, long)` seam and is now directly unit-covered (valid, whitespace-trim, null, blank, garbage). The env-set override itself remains documented-not-asserted (env immutability), unchanged.
- **Delete `OnlineRecommendationEngineTest`** with the engine.
- **Full suite** green confirms nothing referenced the deleted engine (the build would fail otherwise) and both ports still wire their recall service with the inherited default. `RecSysServerIntegrationTest`/`RecSysServerRegressionTest`, `OnlinePredictionServerIntegrationTest`/`OnlinePredictionRegressionTest`, and `MultiChannelRecallServiceTest` pass unmodified.

---

## 6. Out of Scope

- Any shared "registry"/factory unifying the two ports' channel wiring — rejected (different channel sets/quotas; would be a leaky abstraction; convergence already achieved).
- Env-tunable `QuotaPolicy` fractions or per-channel quotas — not requested.
- Removing `MultiChannelRecallService`'s test-convenience `DEFAULT_CHANNEL_TIMEOUT_MS` — still used by its non-production constructors.

---

## 7. Files Changed

| File | Change |
|---|---|
| `online/serving/OnlineRecommendationEngine.java` | Delete (dead code) |
| `src/test/.../online/serving/OnlineRecommendationEngineTest.java` | Delete |
| `service/retrieval/multichannel/RecallConfig.java` | Builder default `channelTimeoutMs` reads `RECALL_CHANNEL_TIMEOUT_MS` (default 200); add package-private `readLongEnv` |
| `serving/RecSysServer.java` | Drop `.channelTimeoutMs(...)` call; remove unused `DEFAULT_CHANNEL_TIMEOUT_MS` constant |
| `online/serving/OnlinePredictionServer.java` | Drop `.channelTimeoutMs(200L)` call |
| `src/test/.../multichannel/RecallConfigTest.java` | Extend: default-timeout + env-name contract |
