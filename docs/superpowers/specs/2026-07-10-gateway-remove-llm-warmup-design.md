# Remove LLM Startup Warmup From the API Gateway

**Date:** 2026-07-10

**Scope:** API Gateway startup in `com.recsys.api.gateway.MicroserviceGatewayServer`
and the LLM proxy in `com.recsys.application.gateway.LlmProxyService`.

## Problem

The API Gateway is a pure routing proxy — it loads no model and holds no
inference runtime. Its only boot-time work beyond wiring is an *LLM warmup*: when
LLM routes are configured, `main()` reads `LLM_WARMUP_ENABLED` (default `true`)
and `registerLlmRoutes` fires `LlmProxyService.warmUp()` once per LLM route. Each
call issues a best-effort GET to the upstream health path to seat the pooled
connection (DNS + TCP + TLS + HTTP/2 preface).

This warmup adds startup surface area — an env flag, a returned
`List<CompletableFuture<Void>>`, an extra method on `LlmProxyService`, and two
dedicated test classes — for a marginal benefit: it only pre-seats the *first*
LLM connection, and only when an LLM upstream is reachable at boot. Removing it
makes the gateway a lean lazy proxy: connections are established on the first
real request instead of at startup, and cached responses (`LlmResponseCache`)
still serve immediately without any boot-time dependency on upstreams.

## Goal

Remove the LLM startup warmup path entirely so the gateway starts leaner and its
startup code is simpler, with **no change** to request-time routing, caching,
authentication, rate-limiting, circuit-breaking, retry, timeout, or health
behavior.

## Non-goals

- Changing any request-time behavior of `LlmProxyService.serve(...)`.
- Changing the tuned LLM `ClientFactory` (`LLM_CONNECT_TIMEOUT_MS`,
  `LLM_IDLE_TIMEOUT_MS`, `LLM_PING_INTERVAL_MS`) — HTTP/2 keepalive stays.
- Changing `LlmResponseCache`, token budgets, or any cache behavior.
- Changing the canonical `/api/recommend` route or any proxy route.
- Adding a readiness gate.

## Architecture

Delete warmup at both layers it touches:

1. **`LlmProxyService`** — remove the public `warmUp()` method and its javadoc.
   No fields or constructor arguments change; the `route`, `webClient`, and the
   `URI`/`route.healthUri()` usages remain because they are still used by the
   forwarding path and by `GatewayHealthService`.

2. **`MicroserviceGatewayServer`** — `registerLlmRoutes` no longer takes a
   `warmupEnabled` flag and returns `void` instead of
   `List<CompletableFuture<Void>>`; it only constructs and registers each
   `LlmProxyService`. `main()` no longer reads `LLM_WARMUP_ENABLED`, no longer
   collects or comments on warmup futures, and drops the now-unused `ArrayList`
   and `CompletableFuture` imports.

The `LLM_WARMUP_ENABLED` environment variable is fully retired — with no warmup
code it can only mislead. It is removed from operator documentation.

## Configuration and compatibility

- `LLM_WARMUP_ENABLED` is removed from `.claude/CLAUDE.md`'s key-env-vars list.
  Setting it in an environment becomes an inert no-op (unknown vars are ignored),
  so existing deployments that still set it keep working; they simply lose the
  boot-time ping.
- All other gateway env vars, routes, and policies are unchanged.
- The historical warmup design/plan documents under `docs/superpowers/specs/` and
  `docs/superpowers/plans/` are left as-is — they are an archival record of the
  original change, not live documentation.

## Testing

- Delete `LlmProxyServiceWarmupTest` — it exclusively exercises `warmUp()`.
- Delete `LlmGatewayWarmupIntegrationTest` — it exclusively exercises the
  `registerLlmRoutes` warmup wiring (enabled hits the health path, disabled does
  not); both branches disappear with the flag.
- No new tests are required: removing warmup means gateway startup performs no
  upstream I/O, which is the trivially-guaranteed post-condition the deleted
  "disabled" test used to assert. The remaining gateway integration and
  route-table tests continue to cover registration and forwarding.
- The focused gateway test suite and the full Maven test suite must pass before
  completion.

## Expected outcome

The gateway startup path is smaller and does no upstream I/O at boot. First LLM
requests lazily establish their connection (a one-time cost previously paid at
startup); cached responses are unaffected and continue to serve immediately.
