# Gateway LLM Client Warmup — Design

**Date:** 2026-07-04
**Status:** Approved (pending spec review)
**Component:** API Gateway (`com.recsys.api.gateway` / `com.recsys.application.gateway`)

## Problem

The gateway's LLM reverse proxy (`LlmProxyService`) builds its Armeria `WebClient`
with only a response timeout set
([`LlmProxyService.java:105-107`](../../../src/main/java/com/recsys/application/gateway/LlmProxyService.java)):

```java
this.webClient = WebClient.builder(route.baseUri().toString())
        .responseTimeoutMillis(timeout.toMillis())
        .build();
```

Two gaps follow from this:

1. **No pool/connection tuning.** It relies on Armeria's default global `ClientFactory`.
   There is no dedicated `connectTimeout` — a dead or slow upstream ties up the request for
   the full 120 s response timeout on *connect*, and there is no HTTP/2 keepalive to keep pooled
   connections healthy through idle/NAT windows.
2. **No pre-connect.** The first real user request pays the entire cold cost — DNS + TCP +
   TLS handshake + HTTP/2 preface — precisely when a user is already waiting on a slow LLM call.

This is the least-engineered of the gateway's preloaded resources: Redis builds its pool eagerly
(`LettuceRedisExecutor`), the ONNX path warms every variant at startup (`ModelRuntimeProvider`),
but the LLM client does neither.

## Goals

- Give the LLM path a **tuned, injected `ClientFactory`** (bounded connect behavior + HTTP/2 keepalive).
- **Pre-connect at boot** so the first request reuses an already-seated connection.
- Keep it **best-effort**: never delay or fail gateway startup, matching the repo's lazy-eager convention.

## Non-Goals

- No changes to the non-LLM proxy routes (`GatewayProxyService`) — LLM routes only.
- No blocking/readiness-gated warmup — the gateway has no `/ready` gate and we will not add one.
- No integration against a real Ollama/LLM endpoint; all tests use in-process Armeria stubs.

## Decisions (locked during brainstorming)

| Decision | Choice |
|---|---|
| Scope | Both: tuned `ClientFactory` **and** startup pre-connect |
| Factory scope | LLM routes only (smallest blast radius) |
| Warmup behavior | Async, best-effort, **on by default** (`LLM_WARMUP_ENABLED=true`) |
| Warmup location | Instance method `LlmProxyService.warmUp()` — reuses the service's own client |
| No LLM routes configured | Skip building the factory and warmup entirely |

## Components

### 1. `ClientFactory llmClientFactory` (in `MicroserviceGatewayServer.main`)

Built once, near the existing LLM setup (~line 72), **only when `llmRoutes` is non-empty**.
Env-driven tunables (Armeria 1.28.4):

| Env var | Default | Maps to |
|---|---|---|
| `LLM_CONNECT_TIMEOUT_MS` | 2000 | `ClientFactoryBuilder.connectTimeout(Duration)` |
| `LLM_IDLE_TIMEOUT_MS` | 60000 | `ClientFactoryBuilder.idleTimeout(Duration)` |
| `LLM_PING_INTERVAL_MS` | 20000 | `ClientFactoryBuilder.pingIntervalMillis(long)` (HTTP/2 keepalive) |

The `connectTimeout` is the primary correctness win: it is independent of the 120 s response
timeout, so a dead upstream fails fast instead of holding a slot open.

> **Implementation note:** exact `ClientFactoryBuilder` method names are verified against
> Armeria 1.28.4 during implementation (TDD compile step catches drift). `pingIntervalMillis`
> vs `pingInterval(Duration)` and the availability of any per-endpoint connection cap are
> confirmed there; the tunable *set* above is the intent.

Closed in the existing shutdown hook alongside `server.stop()`.

### 2. `LlmProxyService` constructor change

Add a `ClientFactory` parameter and use it:

```java
this.webClient = WebClient.builder(route.baseUri().toString())
        .factory(clientFactory)
        .responseTimeoutMillis(timeout.toMillis())
        .build();
```

Both existing constructor overloads thread the factory through. The shorter overload passes the
JVM-shared default `ClientFactory.ofDefault()` so existing tests compile and behave unchanged
(backward-compatible).

### 3. `LlmProxyService.warmUp()`

New instance method, returns `CompletableFuture<Void>`. Mirrors `GatewayHealthService.checkRoute`:

```java
public CompletableFuture<Void> warmUp() {
    String target = /* path (+query) from route.healthUri() */;
    return webClient.get(target).aggregate()
            .thenAccept(agg -> log.info("LLM warmup for {} -> {}", route.name(), agg.status()))
            .exceptionally(t -> { log.warn("LLM warmup for {} failed (non-fatal): {}",
                    route.name(), t.toString()); return null; })
            .toCompletableFuture();
}
```

Because it uses the service's **own** `webClient` (built from `llmClientFactory`), the connection
it seats is the exact pooled connection real requests reuse. Uses `route.healthUri()`
(package-private; `LlmProxyService` is in the same `application.gateway` package).

### 4. `EnvVars.readBool(String, boolean)`

Small new helper consistent with `readInt`/`readLong`/`readDouble`. Parses `true`/`1`/`yes`
(case-insensitive) as true; blank/unset returns the default.

## Boot Flow (`MicroserviceGatewayServer.main`)

1. Split routes into `llmRoutes` / `proxyRoutes` (unchanged).
2. **If `llmRoutes` is empty, skip** the factory + warmup block entirely.
3. Read the new env knobs; build `llmClientFactory` before the `llmRoutes` loop.
4. In the existing loop, pass `llmClientFactory` into each `new LlmProxyService(...)` and register
   the route as today.
5. After registration, if `EnvVars.readBool("LLM_WARMUP_ENABLED", true)`, call
   `llmProxyService.warmUp()` and attach a logging `whenComplete`. **Fire-and-forget** — not joined,
   so `server.start()` is never delayed.
6. Extend the shutdown hook to `llmClientFactory.close()`.

## Error Handling

All failure modes are non-fatal, matching the lazy-eager convention (constructing against a down
dependency never fails startup):

- **Upstream down/slow at boot** → warmup future completes exceptionally → logged at `warn`,
  startup proceeds. Pool stays cold; the first real request re-attempts connect (today's behavior).
- **No LLM routes configured** → factory not built, no warmup, no idle event-loop group.
- **`LLM_WARMUP_ENABLED=false`** → factory still tuned; no pre-connect.

## Testing (TDD)

Tests live with the existing gateway tests, using Armeria `ServerExtension` stub upstreams
(already the suite's pattern):

1. **`EnvVars.readBool`** — `true`/`1`/`yes`/`TRUE` → true; `false`/`0`/unset/blank → default.
2. **`warmUp()` success** — stub upstream serving the health path; assert the future completes and
   the upstream recorded exactly one GET to the health path.
3. **`warmUp()` failure is non-fatal** — route points at a dead port; assert the returned future
   completes (exception handled internally) and nothing escapes.
4. **Factory `connectTimeout`** — construct with a small `connectTimeout`, point at an unroutable
   address, assert the request fails fast (well under the 120 s response timeout).
5. **Backward-compat** — the no-factory constructor overload still compiles and behaves; existing
   `LlmProxyService` tests unchanged.

## Files Touched

- `src/main/java/com/recsys/application/gateway/LlmProxyService.java` — constructor param + `warmUp()`.
- `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java` — factory build, wiring,
  warmup call, shutdown close, no-LLM-routes guard.
- `src/main/java/com/recsys/config/EnvVars.java` — `readBool`.
- Tests: gateway test package (`EnvVars`, `LlmProxyService` warmup + factory).
