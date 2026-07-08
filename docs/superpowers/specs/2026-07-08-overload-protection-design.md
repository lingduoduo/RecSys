# Overload Protection Hardening — Design

**Date:** 2026-07-08
**Status:** Approved (design)
**Author:** brainstormed with Claude Code

## Problem

An investigation of how the system controls online request frequency and sheds overload
found a solid, layered stack (gateway token-bucket rate limiting, online Redis rate
limiting, concurrency load-shedders on Model 8080 and Online 7010, per-route circuit
breakers, request/recall timeouts). The production k8s configmap already **enables** the
gateway (`GATEWAY_RATE_LIMIT_RPS=100`) and online (`ONLINE_REDIS_RATE_LIMIT_QPS=200`)
limits — the earlier "disabled by default" note referred only to bare-`mvn-exec` code
defaults.

Two genuine gaps remain, plus the existing limit values are undocumented:

1. **RecSys Serving (6010) has no admission control or rate limiting** — only a 200ms
   per-channel recall timeout bounds its work. A traffic spike can pile unbounded
   in-flight recall/ranking work with no backpressure.
2. **`WorkerBulkhead` is tested but never wired in production** — both serving servers
   (6010, 7010) use `Executors.newFixedThreadPool(nCpu*2)` with an **unbounded**
   `LinkedBlockingQueue`, so a slow dependency backs up work without bounded-queue
   rejection. The class comment even says "Production callers should supply a
   WorkerBulkhead" — but they don't.
3. **The existing limit values are undocumented** — the online cap is a single global
   200 QPS ceiling (fixed 1s window); the rationale, shape (global vs per-caller), and
   the boundary-burst caveat are not written down anywhere.

## Goals

- RecSys Serving 6010 rejects excess concurrent load with backpressure (429 +
  Retry-After) instead of piling unbounded in-flight work, mirroring the proven Online
  7010 pattern.
- The recall executors on 6010 and 7010 have a **bounded** work queue; overflow sheds
  **per channel** (graceful empty result), never crashing the request.
- The existing rate-limit / load-shed values are documented with their rationale, so
  operators understand what each protects and how to tune it.

## Non-Goals

- Changing the existing numeric limit values (`ONLINE_REDIS_RATE_LIMIT_QPS=200`, gateway
  100 RPS, etc.) — without load-test data, blind numeric changes are riskier than
  documenting them. Real tuning is a separate, data-driven exercise.
- Adding a frequency (QPS) cap to RecSys 6010 — this iteration adds **concurrency
  admission only** (the proven, low-risk pattern). A Redis QPS cap on 6010 can follow.
- Changing the online limiter's algorithm (fixed-window → token bucket) or its
  global-vs-per-caller shape.
- Any change to Model 8080 or the gateway (already protected).

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Scope | RecSys 6010 admission gate + wire WorkerBulkhead (6010 & 7010) + document existing values |
| 6010 protection | **Concurrency admission only** (mirror Online 7010), not a frequency cap |
| Bulkhead queue capacity | Default **poolSize × 4** (bounded backlog), env-tunable |
| Bulkhead overflow behavior | **Graceful per-channel degradation** — a rejected channel yields an empty result + health-monitor backoff, request proceeds |
| Value review | **Docs-only** — document rationale, do not blind-change numbers |

## Architecture / Changes

### 1. RecSys 6010 concurrency admission control

Mirror the Online 7010 pattern (`OnlineLoadShedder` + `OnlineAdmissionControl`):

- **Load shedder:** reuse `OnlineLoadShedder` — it is a generic lock-free CAS `inFlight`
  gate (`tryAcquire`/`release`/`shouldDrain`/`retryAfterSeconds`), not online-specific.
  Construct a RecSys-serving instance from catalog config.
- **Admission decorator:** the existing `OnlineAdmissionControl` couples to
  `OnlineServingMetricsService`. Generalize it: extract the rejection-metric dependency
  behind a minimal callback (e.g. a `Runnable onReject` or a small
  `AdmissionMetrics` interface with `recordRejected()`), so the same decorator wraps both
  7010 (backed by `OnlineServingMetricsService::recordRejected`) and 6010 (backed by a
  no-op or a RecSys counter). No behavior change for 7010 — it passes the same callback
  it uses today.
- **Applied to the expensive routes only:** `/getrecommendation` (`ROUTE_RECOMMENDATION`
  + `ROUTE_RECOMMENDATION_ALIAS`), `/v2/recommend` (`ROUTE_V2_RECOMMEND`), and `/similar`
  (`ROUTE_SIMILAR`) — these run recall/ranking/embedding work. The cheap routes (health,
  item/user metadata from in-memory `DataManager`, `setembedding` admin) stay unguarded.
- **Readiness drain:** on overload/shutdown, `/health` returns 503 when
  `shedder.shouldDrain()` (utilization ≥ drain threshold), matching 7010. RecSys's
  `/getrecommendation` health route is `RecommendationService.Health` today; wire a
  drain-aware health response.
- **Rejection response:** HTTP **429** with `Retry-After` (≥1s) and a small JSON body,
  identical shape to `OnlineAdmissionControl`.

**Config (env → default):**
- `CATALOG_MAX_CONCURRENT_REQUESTS` → **64** (same as `ONLINE_MAX_CONCURRENT_REQUESTS`)
- `CATALOG_DRAIN_UTILIZATION` → **0.90** (same as `ONLINE_DRAIN_UTILIZATION`)

### 2. Wire `WorkerBulkhead` into the recall executors (6010 + 7010)

Replace the unbounded fixed pool at `RecSysServer.java:87-89` and
`OnlinePredictionServer.java:84-86`:
```java
// before
ExecutorService executor = Executors.newFixedThreadPool(nCpu*2, r -> new Thread(r, "recall-channel"));
// after
WorkerBulkhead recallBulkhead = new WorkerBulkhead("recall", nCpu*2, queueCapacity);
ExecutorService executor = recallBulkhead.asExecutorService();
```
`WorkerBulkhead` wraps a `ThreadPoolExecutor` with a bounded `ArrayBlockingQueue` and no
custom rejection handler, so a full queue throws `RejectedExecutionException`.

**Graceful rejection (the critical part):** `MultiChannelRecallService` submits each
channel via `CompletableFuture.supplyAsync(task, executor)`. A full bounded queue makes
`executor.execute()` — and thus `supplyAsync` — throw `RejectedExecutionException`
**synchronously**, which today would abort the whole request. Wrap the per-channel
submit (`MultiChannelRecallService.java:117-125`) in a try/catch:
```java
CompletableFuture<ChannelResult> future;
try {
    future = CompletableFuture.supplyAsync(() -> { ... }, executor)
            .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
            .exceptionally(ex -> new ChannelResult(name, List.of(), ex));
} catch (RejectedExecutionException rex) {
    healthMonitor.recordFailure(name);
    future = CompletableFuture.completedFuture(new ChannelResult(name, List.of(), rex));
}
futures.add(future);
```
A rejected channel degrades to an empty result and records a health-monitor failure
(identical treatment to a channel timeout → the channel backs off), and the request
proceeds with whatever channels were admitted. This is the bulkhead's purpose: bound the
work and shed the overflow per channel, not fail the request.

**Config:** `RECALL_BULKHEAD_QUEUE_CAPACITY` → default **poolSize × 4**
(= `nCpu*2*4`). Both serving servers read it; a bounded backlog absorbs bursts while
capping total queued work.

### 3. Document the existing limit values

Docs-only. No numeric changes.

- Add rationale comments in `k8s/base/configmap.yaml` next to each limit key: what it
  protects, its shape (global vs per-caller/route), and its relationship to the
  concurrency gates. Add the two new `CATALOG_*` keys.
- Add a **"Capacity & rate limits"** section to a runbook
  (`docs/runbooks/overload-protection.md`) that lays out the full stack: the online
  global 200 QPS ceiling and its fixed-window boundary-burst caveat (up to ~2× limit
  across a window boundary), the per-caller gateway limits, the per-instance vs
  cluster-wide distinction, and an explicit note that real values require load-test
  validation.

## Testing

- **Admission gate unit test** — the generalized decorator rejects (429) above the
  concurrency limit, releases on response completion, and reports drain at the
  utilization threshold. Confirm the 7010 wiring is unchanged (the online metrics
  callback still fires on reject).
- **Bulkhead graceful-rejection test** — a `MultiChannelRecallService` backed by a
  `WorkerBulkhead` saturated to its queue capacity produces an empty `ChannelResult` for
  the rejected channel (not a thrown request) and records a health-monitor failure; the
  overall recall still returns results from admitted channels.
- **Config render assertions** — `kubectl kustomize k8s/base` renders the new
  `CATALOG_MAX_CONCURRENT_REQUESTS` / `CATALOG_DRAIN_UTILIZATION` /
  `RECALL_BULKHEAD_QUEUE_CAPACITY` keys.
- **Full suite** — `mvn test` green (JDK 17); existing `WorkerBulkheadTest` and online
  admission/load-shed tests must still pass.

## Risks & open items

- **Generalizing `OnlineAdmissionControl`** touches the live 7010 path. The refactor
  must be behavior-preserving for 7010 (same 429, same metric on reject) — covered by
  the existing online admission tests plus the new gate test.
- **Bulkhead queue capacity (poolSize × 4).** A starting heuristic, not load-validated.
  Too small → premature per-channel shedding under normal bursts; too large → approaches
  the unbounded behavior it replaces. Env-tunable; the runbook flags load-testing.
- **6010 route protection scope.** Guarding only the recall/ranking/similar routes is a
  judgment call; if a "cheap" route turns out expensive under load, it can be added.
- **No frequency cap on 6010.** Concurrency admission bounds *in-flight* work, not raw
  QPS; a very fast, cheap-per-request flood could still churn. Accepted for this
  iteration (a Redis QPS cap on 6010 is a documented follow-up).
