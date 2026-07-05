# Graceful Shutdown Consistency — Design

**Date:** 2026-07-04
**Status:** Approved (pending spec review)
**Component:** Serving entrypoints + load shedding (`api/*`, `loadshed`, `health`, `infrastructure/redis/sharding`)

## Problem

The four services drain four different ways on SIGTERM. Only the Spring model-serving app
implements the full "advertise DOWN, then drain in-flight" pattern. The three Armeria servers are
inconsistent and, in two cases, incomplete:

| Service | Stack | Drain window | Readiness flips on SIGTERM | Gap |
|---|---|---|---|---|
| ModelApplication (8080) | Spring | 30s (`server.shutdown: graceful`) | Yes | — (reference impl) |
| OnlinePredictionServer (7010) | Armeria | 1s/30s | **No** (utilization-only) | Readiness/admission ignore SIGTERM |
| RecSysServer (6010) | Armeria | **None** | No | In-flight requests cut off on stop |
| MicroserviceGatewayServer (8010) | Armeria | **None** | No | In-flight requests cut off on stop |

Concrete defects:
1. `RecSysServer` and `MicroserviceGatewayServer` set no `gracefulShutdownTimeoutMillis`, so
   `server.stop()` completes immediately and in-flight requests can be cut off.
2. The Armeria `OnlineLoadShedder` has no shutdown concept (`grep` confirms `markShuttingDown`
   exists only in the Spring `LoadShedder`), so `OnlineHealthService./health/ready` stays `200`
   and admission control keeps accepting new requests during drain.
3. Drain thresholds differ: Spring `maxInFlightUtilization = 0.95` vs `OnlineLoadShedder = 0.90`.
4. `ShardTopologyProvider.stop()` uses `scheduler.shutdownNow()` with no await — abrupt vs the
   graceful `shutdown()→await→shutdownNow()` pattern used elsewhere (`GracefulExecutors`).

## Goals

Bring the Armeria servers to a consistent graceful-shutdown baseline:
- All three Armeria servers wait for in-flight requests to drain before stopping.
- The online server flips readiness to 503 and sheds new load on SIGTERM, matching the Spring app.
- Drain thresholds are aligned; the topology scheduler stops gracefully.

## Non-Goals

- No changes to the Spring `ModelApplication` path (already correct).
- **No `minSampleSize` cold-start guard on the online path.** That guard protects failure-rate and
  latency readiness checks; `OnlineHealthService` has none (readiness is purely `!shouldDrain()`,
  which is `in-flight=0 → ready` at cold start), so the guard would protect nothing. Confirmed
  dropped during brainstorming.
- No readiness-flip for `RecSysServer`/`MicroserviceGatewayServer` (they have no load shedder;
  new-request routing is handled by K8s Endpoint removal). They get the drain window only.
- No unifying of the two load shedders (`LoadShedder` Spring `@Service` w/ Micrometer vs
  `OnlineLoadShedder` plain Armeria CAS gate) — deliberately separate.

## Decisions (locked during brainstorming)

| Decision | Choice |
|---|---|
| Approach | Minimal edits mirroring existing patterns + one shared helper |
| Drain window values | 1s quiet / 30s max on all Armeria servers (matches online, < K8s 60s grace) |
| Window config | Hardcoded shared constant (not env-configurable — YAGNI; online hardcodes today) |
| Threshold alignment | `OnlineLoadShedder` `0.90 → 0.95` to match Spring |
| Online SIGTERM API | Mirror the Spring `LoadShedder` API: `markShuttingDown()`/`isShuttingDown()` |
| Topology stop | Inline graceful `shutdown()→awaitTermination(1s)→shutdownNow()` (no loadshed dep) |
| minSampleSize on online | Dropped (N/A) |

## Components

### 1. `GracefulServers` (new, `com.recsys.loadshed`)

```java
public final class GracefulServers {
    // 30s max drain sits below the K8s terminationGracePeriodSeconds: 60 so the pod is
    // never SIGKILLed mid-drain. 1s quiet period matches the online server's existing value.
    static final long QUIET_PERIOD_MS = 1_000L;
    static final long TIMEOUT_MS = 30_000L;

    public static ServerBuilder applyShutdownWindow(ServerBuilder sb) {
        return sb.gracefulShutdownTimeoutMillis(QUIET_PERIOD_MS, TIMEOUT_MS);
    }
}
```

Adopted by `RecSysServer`, `MicroserviceGatewayServer`, and `OnlinePredictionServer` (the last
replaces its inline `.gracefulShutdownTimeoutMillis(1_000L, 30_000L)` — no behavior change).

### 2. `OnlineLoadShedder` (modify)

- Add `private volatile boolean shuttingDown = false;`, `public void markShuttingDown()`,
  `public boolean isShuttingDown()`. `shuttingDown` is one-way (never reset), mirroring Spring.
- `tryAcquire()`: return `false` immediately when `shuttingDown` (reject new work during drain),
  incrementing `rejectedRequests`.
- `shouldDrain()`: `return shuttingDown || utilization() >= drainUtilization;`
- `snapshot()`: `suggestedWeight = shuttingDown ? 0 : <existing formula>`; add a `shuttingDown`
  field to the `Snapshot` record (additive — existing accessor-based consumers unaffected;
  the only positional `new Snapshot(...)` is inside `snapshot()`).
- Change `DEFAULT_DRAIN_UTILIZATION` `0.90 → 0.95`. (`ONLINE_DRAIN_UTILIZATION` override unchanged.)

### 3. `OnlinePredictionServer` (modify)

- Builder: replace inline `.gracefulShutdownTimeoutMillis(1_000L, 30_000L)` with
  `GracefulServers.applyShutdownWindow(sb)` (or a call within the fluent chain).
- Shutdown hook: add `loadShedder.markShuttingDown();` as the **first** statement, before
  `server.stop().join()`.

### 4. `OnlineHealthService` (modify)

- Add `"shuttingDown", load.shuttingDown()` to the JSON body. Readiness already flips via
  `!loadShedder.shouldDrain()` — no logic change, observability parity only.

### 5. `ShardTopologyProvider.stop()` (modify)

```java
public void stop() {
    if (scheduler == null) return;
    scheduler.shutdown();
    try {
        if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
            scheduler.shutdownNow();
        }
    } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```

Bounded 1s await; interrupt-safe; still null-safe on the scheduler.

## Shutdown Sequence (online server, the meaningful change)

1. K8s marks pod Terminating → removed from Service Endpoints; `preStop: sleep 5` delays SIGTERM.
2. SIGTERM → JVM shutdown hook. First line `loadShedder.markShuttingDown()`:
   - `/health/ready` → `503` (`shouldDrain()` true) — defense-in-depth for LBs reading readiness.
   - `OnlineAdmissionControl.tryAcquire()` → false → `429 Retry-After` for new requests.
   - One shared `loadShedder` instance backs both admission control and health, so one flag flips both.
3. `server.stop().join()` drains in-flight within 1s quiet / 30s max.
4. Existing teardown unchanged (publisher, learner scheduler, recall executor, topology, Redis).

`RecSysServer`/gateway: drain window only — in-flight requests survive `server.stop()` up to 30s.

## Error Handling / Edge Cases

- `markShuttingDown()` idempotent; `shuttingDown` one-way.
- `snapshot()` reads `shuttingDown` as a plain volatile read; no locking, no new allocation beyond
  the added record field.
- `ShardTopologyProvider.stop()`: if refresh task doesn't finish in 1s, `shutdownNow()` interrupts
  it — harmless (keeps last-good snapshot). Null-safe; interrupt-safe.

## Testing (TDD)

- **`GracefulServersTest` (new)** — build a `Server` via `applyShutdownWindow(Server.builder().http(0))`;
  assert `server.config().gracefulShutdownQuietPeriod()` == `Duration.ofSeconds(1)` and
  `gracefulShutdownTimeout()` == `Duration.ofSeconds(30)`. (Armeria 1.28.4 `ServerConfig` exposes both.)
- **`OnlineLoadShedderTest` (extend)** — after `markShuttingDown()`: `tryAcquire()` false,
  `shouldDrain()` true, `snapshot().shuttingDown()` true, `snapshot().suggestedWeight()` == 0.
  A fresh low-utilization instance still accepts. Threshold: 60/64 (~0.9375) not draining, 61/64
  (~0.953) draining, confirming the 0.95 default.
- **`OnlineHealthServiceTest` (extend)** — with a shared `OnlineLoadShedder`, `/health/ready`
  returns `200` before and `503` after `markShuttingDown()`; body contains `shuttingDown`.
- **`ShardTopologyProviderTest` (extend)** — after `stop()`, `scheduler.isShutdown()` is true;
  `stop()` on a never-started provider (null scheduler) does not throw.
- **Drain window wiring** — the per-server one-line adoptions are verified by compile;
  `GracefulServersTest` covers the helper values. If `RecSysServerIntegrationTest` /
  `GatewayServerIntegrationTest` expose the built `Server`, add a `gracefulShutdownTimeout()`
  assertion there too.

## Files Touched

- Create: `src/main/java/com/recsys/loadshed/GracefulServers.java`
- Modify: `src/main/java/com/recsys/loadshed/OnlineLoadShedder.java`
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`
- Modify: `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Modify: `src/main/java/com/recsys/health/OnlineHealthService.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/sharding/ShardTopologyProvider.java`
- Tests: `GracefulServersTest` (new), `OnlineLoadShedderTest`, `OnlineHealthServiceTest`,
  `ShardTopologyProviderTest` (extend).
