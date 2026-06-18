# Shared Recall Core Design (Sub-project 1 of 3)

_Date: 2026-06-18_
_Scope: `service/retrieval/` — make the multichannel recall quota policy injectable and add a per-port `RecallConfig`, with zero behavior change to port 6010 (`RecSysServer`)._
_Part of: unifying 6010 and 7010 onto one recall design. Sub-project 2 (adopt on 7010) and sub-project 3 (converge config) depend on this and are out of scope here._

---

## 1. Problem Statement

`MultiChannelRecallService` is already port-agnostic in its types (it consumes the shared `RecommendationQuery` and returns `List<MovieCandidate>`). The one thing hardcoding it to port 6010's channel set is the quota policy: `recall()` calls the static `QuotaSpec.warm(limit)` / `QuotaSpec.cold(limit)` ([MultiChannelRecallService.java:77-79](../../src/main/java/com/recsys/service/retrieval/multichannel/MultiChannelRecallService.java)), and those statics bake in 6010's channel **names** (`embedding`, `trending`, `genre_history`, `popularity`, `cold_start`) and percentages ([QuotaSpec.java:17-47](../../src/main/java/com/recsys/service/retrieval/coldstart/QuotaSpec.java)).

Port 7010 (`OnlinePredictionServer`) will have a different channel set (e.g. an online-recent-history channel, no `genre_history`). To let both ports share one recall service, the quota policy must become injectable and per-port wiring must be bundled into a config object — **without changing 6010's output at all**.

This sub-project delivers only that foundation. It does not touch 7010.

---

## 2. Chosen Approach

Introduce a configurable `QuotaPolicy` (Approach A: fraction maps + a shared slot-rounding helper that generalizes the current logic) and a per-port `RecallConfig`. `MultiChannelRecallService` takes a `QuotaPolicy`; its existing constructors default to `QuotaPolicy.defaultMovie()`, which encodes 6010's exact numbers via the general helper. `RecSysServer` is re-wired to build the service through `RecallConfig`. The legacy `QuotaSpec.warm/cold` statics are kept untouched as the equivalence oracle.

**Invariant:** 6010's recall output is byte-identical before and after — locked by an equivalence test against `QuotaSpec`.

---

## 3. Components

### 3.1 `QuotaPolicy` (new — `service/retrieval/coldstart/QuotaPolicy.java`)

```java
public record QuotaPolicy(
        Map<String, Double> warmFractions, String warmResidualChannel,
        Map<String, Double> coldFractions, String coldResidualChannel) {

    public QuotaPolicy { /* validate + copy to insertion-ordered unmodifiable maps */ }

    public QuotaSpec warm(int limit);   // -> slots via shared helper
    public QuotaSpec cold(int limit);

    public static QuotaPolicy defaultMovie();  // 6010's numbers
}
```

- `warmFractions` / `coldFractions` are the **non-residual** channels in iteration order; the residual channel is named separately and receives `limit - sum(others)`.
- Compact constructor: copy each map into an insertion-ordered unmodifiable `LinkedHashMap`; reject null maps/residual; reject a residual channel that also appears in its own fraction map; reject negative fractions.
- `warm`/`cold` reject `limit <= 0` (mirrors `QuotaSpec`).

**Shared slot-rounding helper** (private static):
```
remaining = limit
for (channel, fraction) in orderedFractions:          // residual excluded
    slot = clamp(round(fraction * limit), 0, remaining)
    result[channel] = slot;  remaining -= slot
result[residualChannel] = max(0, remaining)
return new QuotaSpec(result)
```

`defaultMovie()` is constructed from this helper with:
- warm: `{embedding 0.60, trending 0.20, genre_history 0.15}`, residual `popularity`
- cold: `{cold_start 0.50, trending 0.20, popularity 0.20}`, residual `genre_history`

This reproduces `QuotaSpec.warm/cold` exactly (verified by trace; locked by test §6). The clamp only bites on rounding overshoot — exactly what `QuotaSpec.cold`'s explicit `min(...)` clamps already do, and it never triggers for warm.

### 3.2 `RecallConfig` (new — `service/retrieval/multichannel/RecallConfig.java`)

Per-port bundle + builder:

```java
public record RecallConfig(
        List<RecallChannel> channels,
        QuotaPolicy quotaPolicy,
        long channelTimeoutMs,
        ExecutorService executor,
        ChannelHealthMonitor healthMonitor,
        FaultInjector faultInjector,
        EmbeddingStore userEmbeddingStore) {

    public static Builder builder();
}
```

Builder validates: `channels` non-empty, `executor` and `healthMonitor` non-null, `channelTimeoutMs >= 1`; defaults `faultInjector = FaultInjector.NOOP`, `quotaPolicy = QuotaPolicy.defaultMovie()`, `userEmbeddingStore = null` (cold-start detection disabled when null, unchanged). `userEmbeddingStore` and `faultInjector` are optional/nullable, matching today's semantics.

### 3.3 `MultiChannelRecallService` (modify)

- New field `QuotaPolicy quotaPolicy`.
- `recall()` replaces `QuotaSpec.cold/warm(limit)` with `quotaPolicy.cold/warm(limit)` ([lines 77, 79](../../src/main/java/com/recsys/service/retrieval/multichannel/MultiChannelRecallService.java)). Cold-start detection logic (the `userEmbeddingStore.getEmbedding` probe and the `NumberFormatException → cold` fallback) is unchanged.
- New `static MultiChannelRecallService from(RecallConfig config)` factory.
- New constructor accepting the existing 6 args **plus** `QuotaPolicy`. The existing 1-arg, 5-arg, and 6-arg constructors are preserved and delegate with `QuotaPolicy.defaultMovie()` — so every current caller and test is byte-identical.

### 3.4 `RecSysServer` (modify — `serving/RecSysServer.java:92-105`)

Build the service via `RecallConfig.builder()` with the same channels, `QuotaPolicy.defaultMovie()`, `DEFAULT_CHANNEL_TIMEOUT_MS`, executor, `ChannelHealthMonitor`, `FaultInjector.NOOP`, and `userEmbCache`. Output identical.

### 3.5 `QuotaSpec` (unchanged)

`QuotaSpec` stays as the per-request output type. Its `warm/cold` statics are **kept untouched** — referenced only by `MultiChannelRecallService` (now superseded by `QuotaPolicy`) and `QuotaSpecTest`. They serve as the equivalence oracle for `defaultMovie()`.

---

## 4. Architecture After Changes

```
                         per-port config
  RecSysServer (6010) ──► RecallConfig{ channels, QuotaPolicy.defaultMovie(), ... }
                                   │
                                   ▼
                    MultiChannelRecallService.from(config)
                                   │  recall(query, limit)
                                   ▼
              isCold ? quotaPolicy.cold(limit) : quotaPolicy.warm(limit)
                                   │  (QuotaPolicy → shared helper → QuotaSpec)
                                   ▼
                       quota-aware two-phase merge  (unchanged)

  QuotaSpec.warm/cold (legacy statics) ── kept as equivalence oracle for tests
```

Sub-project 2 will add `RecallConfig` for 7010 with its own channels + `QuotaPolicy`; sub-project 3 converges both behind one registry.

---

## 5. Data Flow & Behavior

No change to the request path for 6010. The only substitution is the source of the per-request `QuotaSpec`: `QuotaPolicy.defaultMovie()` instead of the `QuotaSpec.warm/cold` statics, producing identical slot maps. The quota-aware two-phase merge, `ChannelHealthMonitor` backoff, cold-start probe, and `legacyMerge` fallback (when `userEmbeddingStore == null`) are all unchanged.

---

## 6. Testing Strategy

### Unit tests

| Test | Coverage |
|---|---|
| `QuotaPolicyTest` (new) | **Equivalence oracle:** `defaultMovie().warm(L).slots()` equals `QuotaSpec.warm(L).slots()`, and cold likewise, for L ∈ {1,3,5,7,10,12,20,50,100}. Custom policy (`{embedding 0.5, trending 0.3}` residual `popularity`) yields expected slots; residual channel receives the remainder; total never exceeds `limit`. `limit <= 0` throws. Compact-constructor validation: null map, residual-in-fraction-map, negative fraction all rejected. |
| `RecallConfigTest` (new) | Builder rejects empty channels / null executor / null healthMonitor / `timeout < 1`; defaults applied (`FaultInjector.NOOP`, `defaultMovie()`, null `userEmbeddingStore`); `from(config)` wires every field (verify via a recall that honors the injected quota). |
| `MultiChannelRecallServiceTest` (extend) | All existing tests pass unmodified. Add: an injected **custom** `QuotaPolicy` drives the merge (custom warm/cold quotas reflected in the selected candidates). |

### Regression (pass unmodified)

`QuotaSpecTest`, `MultiChannelRecallServiceTest` (existing cases), `RecSysServerIntegrationTest`, `RecSysServerRegressionTest`, `ColdStartChannelTest`, `EmbeddingRecallLoadTest`.

---

## 7. Out of Scope

- **7010 adoption** — sub-project 2 (new `OnlineRecentHistoryChannel`, DTO adapter, wiring, `OnlineLearner` + load-shedding preservation).
- **Config convergence / retiring `OnlineRecommendationEngine`** — sub-project 3.
- **Changing 6010's channel set, quotas, or merge logic** — strictly a refactor; numbers unchanged.
- **Removing `QuotaSpec.warm/cold` statics** — kept as the equivalence oracle; removal (if ever) is a later cleanup.

---

## 8. Files Changed

| File | Change |
|---|---|
| `service/retrieval/coldstart/QuotaPolicy.java` | New — configurable warm/cold fraction maps + shared slot helper + `defaultMovie()` |
| `service/retrieval/multichannel/RecallConfig.java` | New — per-port recall config + builder |
| `service/retrieval/multichannel/MultiChannelRecallService.java` | Inject `QuotaPolicy` (default `defaultMovie()`); `from(RecallConfig)` factory; preserve existing constructors |
| `serving/RecSysServer.java` | Build recall service via `RecallConfig` with identical channels/numbers |
| `src/test/.../coldstart/QuotaPolicyTest.java` | New — equivalence oracle + validation |
| `src/test/.../multichannel/RecallConfigTest.java` | New — builder validation + wiring |
| `src/test/.../multichannel/MultiChannelRecallServiceTest.java` | Extend — injected custom `QuotaPolicy` drives merge |
