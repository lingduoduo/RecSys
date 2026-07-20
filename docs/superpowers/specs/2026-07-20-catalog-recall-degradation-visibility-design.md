# Catalog Recall Degradation Visibility Design

## Objective

On the catalog serving path (port 6010), the recall bulkhead (`WorkerBulkhead`,
~`cores×10` task ceiling) saturates *before* the 64-request concurrency admission
gate rejects. When the bulkhead's bounded queue is full, non-primary recall
channels are rejected and degrade to empty results, so the request still returns
**HTTP 200 with fewer/worse candidates** — no `429`, no log, no metric. The first
symptom of overload is silent recommendation-quality loss that operators cannot
see.

This design makes that degradation **visible** without changing request outcomes:
degraded responses stay `200`. It adds (1) a per-request response header and (2) a
pollable `/health/load` snapshot with cumulative degradation counters and the live
bulkhead state.

## Scope

In scope:

- A `RecallResult` return type carrying the degraded-channel set alongside
  candidates.
- A `RecallDegradationMetrics` cumulative counter (per channel × reason).
- A `CatalogLoadService` exposing `GET /health/load` on 6010.
- An `X-Recall-Degraded` response header on the recommendation/similar/v2 paths.
- Wiring in `RecSysServer`.

Out of scope (explicit non-goals):

- **No** change to request outcomes — degraded `200`s remain `200`s (the chosen
  "visible only" intent; escalation-to-shed is a separate future design).
- **No** Prometheus/Micrometer stack added to 6010 (it has none today; signals are
  surfaced via the existing health scrape).
- **No** change to primary-channel semantics — a rejected/failed **primary**
  channel still throws `PrimaryRecallUnavailableException` and is not counted as
  silent degradation.
- Not addressing the other scalability sharp edges (#4 rate limiter, #5 DR
  pre-scale, #2/#3) — each is a separate spec.

## Background

`MultiChannelRecallService.recall(query, limit, primary)` runs each channel as a
task on the shared recall `WorkerBulkhead` executor. Per channel it builds a
`ChannelResult(name, candidates, error)`:

- Non-primary channel rejected (`RejectedExecutionException`) → `ChannelResult`
  with empty candidates + the exception (`MultiChannelRecallService.java:145-149`).
- Non-primary channel timed out (`orTimeout(RECALL_CHANNEL_TIMEOUT_MS)`) or errored
  → empty candidates + the throwable via `.exceptionally(...)`
  (`MultiChannelRecallService.java:141-143`).
- Primary channel rejected/failed → throws `PrimaryRecallUnavailableException`
  (unchanged).

The `error` is already captured but discarded: `recall()` returns a bare
`List<MovieCandidate>` (`MultiChannelRecallService.java:96-101`), so callers never
learn a channel was dropped. `WorkerBulkhead` already exposes a
`Snapshot(name, active, queued, poolSize, rejected)`
(`WorkerBulkhead.java:71-75`) but nothing reads it on 6010.

6010 (`RecSysServer`) currently exposes only `/health` and `/health/ready` — no
`/metrics`, no `MeterRegistry`.

## Components

### 1. `RecallResult` (record, `application/retrieval/multichannel`)

```
public record RecallResult(List<MovieCandidate> candidates, Set<String> degradedChannels) {
    // candidates and degradedChannels are non-null; degradedChannels is an
    // unmodifiable set of non-primary channel names that returned empty due to
    // rejection/timeout/error. (Note: the record copies via Set.copyOf, whose
    // iteration order is unspecified/JVM-salted; consumers that need a stable
    // order — e.g. the response header — sort the names themselves.)
}
```

**Additive API (no breaking change).** The existing `recall(query, limit)` /
`recallPrimary(query, limit)` methods keep returning `List<MovieCandidate>` — they
have callers beyond 6010 (7010's `OnlineRecommendationService`,
`ModelRetrievalStage`) and ~15 test files that mock them, so their signatures must
not change. Two new methods return the richer type:

```
public RecallResult recallDetailed(RecommendationQuery query, int limit);
public RecallResult recallPrimaryDetailed(RecommendationQuery query, int limit);
```

The shared private `recall(query, limit, primary)` becomes `RecallResult`-producing;
the `List`-returning public methods delegate and return `.candidates()`, the
`*Detailed` methods return the whole `RecallResult`. Only the two header-needing
6010 paths (`RecommendationService.V1`, `RecommendationOrchestrator`) call the
`*Detailed` methods.

Rationale: an explicit return type keeps the degraded-channel data flow visible and
unit-testable (versus a request-scoped `ThreadLocal`/Armeria context attribute that
would hide it), and the additive form keeps the blast radius to the two 6010 paths
that surface the header. Metrics (component 2) are recorded inside the shared
private method regardless of which public entry point is used, so `/health/load`
counters cover every caller automatically.

### 2. `RecallDegradationMetrics` (`application/retrieval/multichannel`)

Thread-safe cumulative counters, no transport dependency:

- `record(String channel, Reason reason)` — increments `(channel, reason)` and
  `degradedRecalls` (once per request that degraded at all).
- `recordTotal()` — increments `totalRecalls`, called once per **non-primary**
  recall invocation (the only path that can silently degrade; primary recalls
  fail loud and are excluded from the denominator so `degradedRatio` stays
  meaningful).
- `Reason` enum: `REJECTED`, `TIMEOUT`, `ERROR` (classified from the throwable:
  `RejectedExecutionException → REJECTED`, `TimeoutException` /
  `CompletionException`-wrapping-timeout `→ TIMEOUT`, else `ERROR`).
- `snapshot()` → `Snapshot(Map<String, Map<Reason, Long>> byChannel, long totalRecalls, long degradedRecalls, double degradedRatio)` where
  `degradedRatio = totalRecalls == 0 ? 0.0 : degradedRecalls / (double) totalRecalls`.

Backed by `ConcurrentHashMap<String, EnumMap<Reason, AtomicLong>>` + `AtomicLong`
totals. Injected into `MultiChannelRecallService` via a new constructor parameter,
threaded through `RecallConfig` + its builder (default: a fresh
`RecallDegradationMetrics` instance, so existing constructors/tests are
unaffected). Production wiring passes the **same** instance to both the recall
service and `CatalogLoadService` so the two share state.

### 3. `CatalogLoadService` (`api/serving`)

An Armeria `HttpService` mirroring 7010's `OnlineOpsService` composition pattern.
Reads `WorkerBulkhead.snapshot()` + `RecallDegradationMetrics.snapshot()` and serves
the JSON below at `GET /health/load`. Read-only; needs only normal gateway auth
(same as `/health`).

**Bulkhead-rejected caveat.** On the catalog path the recall service runs channels
through `recallBulkhead.asExecutorService()` + `CompletableFuture.supplyAsync`, not
`WorkerBulkhead.submit()`. `WorkerBulkhead.rejectedCount` only increments inside
`submit()` (`WorkerBulkhead.java:51`), so the bulkhead `Snapshot.rejected` field is
**always 0** on this path. The authoritative rejection signal is therefore the
`RecallDegradationMetrics` `REJECTED` reason, not the bulkhead snapshot. The
`bulkhead` section of `/health/load` exposes only the meaningful live fields
(`active`, `queued`, `poolSize`); `rejected` is intentionally omitted to avoid a
misleading always-zero gauge.

### 4. `RecSysServer` wiring

Construct one `RecallDegradationMetrics`; pass it into
`RecallConfig.builder().recallMetrics(...)` (feeding the recall service) **and** into
`CatalogLoadService` (with the existing `recallBulkhead`); register
`.service("/health/load", catalogLoadService)`.

## Data Flow

Inside the shared private `recall(query, limit, primary)`:

1. When `primary == false`, `metrics.recordTotal()` once (after the `limit <= 0`
   early return).
2. In the result-collection loop, for each `ChannelResult` with a captured `error`
   (only non-primary results reach here — primary errors throw earlier): classify
   `Reason`, `metrics.record(channel, reason)`, and add `channel` to a local
   insertion-ordered `degradedChannels` set.
3. Merge/rank candidates as today (`legacyMerge` / `quotaMerge`, unchanged).
4. Return `new RecallResult(ranked, unmodifiableSet(degradedChannels))`.

`recallPrimaryDetailed` returns a `RecallResult` with an empty `degradedChannels`
set; primary failures still throw before any result is built.

## Response Header

`X-Recall-Degraded` is set when the recall degraded ≥1 non-primary channel:

- **V1** (`RecommendationService.V1`) reads `RecallResult.degradedChannels()` from
  `recallService.recallDetailed(...)`.
- **V2** (`RecommendationService.V2` → `RecommendationOrchestrator`): the orchestrator
  calls `recallDetailed(...)`, and writes the comma-joined degraded set into the
  existing `RecommendationResult.trace()` map under key `degradedChannels` (alongside
  `candidateCount`/`rankedCount`). The V2 handler reads
  `result.trace().get("degradedChannels")` and sets the header.

```
X-Recall-Degraded: momentum,trending
```

Absent on full-quality responses — its presence is the per-request alert. Channel
names are **sorted alphabetically** and comma-joined, so the header is deterministic
and JVM-stable (the underlying `degradedChannels` set has unspecified iteration order
after `Set.copyOf`; both the V1 helper and the V2 trace-writer sort before joining).
Not set on error responses. (The V2 path also surfaces the same sorted value in the
JSON `trace` map by construction.)

## `/health/load` Response

```json
{
  "recall": {
    "bulkhead": { "poolSize": 16, "active": 16, "queued": 812 },
    "channelDegraded": {
      "momentum": { "rejected": 90, "timeout": 12 },
      "trending": { "rejected": 45 }
    },
    "degradedRatio": 0.07
  }
}
```

`degradedRatio` is the primary alerting signal (degraded requests ÷ total recall
requests). `channelDegraded[*].rejected` is the authoritative bulkhead-rejection
count for this path (see the caveat under component 3); `queued`/`active`
are instantaneous. Zero traffic yields `degradedRatio: 0.0` (never `NaN`).

## Testing

- **`RecallDegradationMetrics`** — concurrent `record`/`recordTotal` from multiple
  threads; snapshot counts and per-`Reason` classification; `degradedRatio` math
  including the zero-traffic (0.0, not NaN) case.
- **`MultiChannelRecallService`** — with a stub executor/channel that throws
  `RejectedExecutionException` or times out: assert `RecallResult.degradedChannels`
  contains exactly the dropped non-primary channels, metrics recorded the right
  `(channel, reason)`, and candidates still merge from surviving channels
  (HTTP-200-equivalent). Assert a **primary** rejection still throws
  `PrimaryRecallUnavailableException` and increments **no** degradation counter.
- **`CatalogLoadService`** — JSON shape and field values from a known
  bulkhead+metrics snapshot; zero-traffic `degradedRatio`.
- **Header (integration)** — a forced non-primary rejection returns `200` with
  `X-Recall-Degraded`; a clean request returns `200` with no such header.

## Acceptance Criteria

1. A recall where ≥1 non-primary channel is rejected/times out returns `200` with an
   `X-Recall-Degraded` header listing exactly those channels; a clean recall has no
   such header.
2. `GET /health/load` on 6010 returns the bulkhead snapshot + cumulative per-channel
   degradation counters + `degradedRatio`, and updates as degradation occurs.
3. Primary-channel failure behavior is unchanged (`PrimaryRecallUnavailableException`,
   not counted as degradation).
4. No request that previously returned `200` now returns a different status.
5. No Prometheus/Micrometer dependency added to 6010.
6. New unit tests cover metrics classification, recall degraded-set population,
   load-snapshot JSON, and header presence/absence.
