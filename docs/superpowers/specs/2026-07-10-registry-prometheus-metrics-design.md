# Prometheus Registry Metrics on the API Gateway

**Date:** 2026-07-10

**Scope:** Gateway consumer observability — a Prometheus `/metrics` endpoint on
`MicroserviceGatewayServer` plus service-registry consumer metrics
(`com.recsys.metrics.GatewayRegistryMetrics`), and two refresh counters on
`ServiceRegistryProvider`. Builds on the merged service registry (#184/#185).

## Background

The registry ships with opt-in `/health` observability (#185), but there are no
scrapeable metrics: an operator running Prometheus cannot alert on the gateway
falling back to static addresses, a stale registry snapshot, or refresh failures.
The other three services already expose Prometheus at `/metrics` via Armeria's
`PrometheusExpositionService`; the Armeria gateway does not.

## Decisions (from brainstorming)

- **Gateway consumer metrics only.** The gateway is the discovery decision point
  ("which upstream did we resolve, and how fresh is that knowledge"). Producer
  heartbeat metrics on the four backends are deferred.
- **Always-on `/metrics`.** The gateway gets a Prometheus endpoint
  unconditionally, matching the other three services. Registry meters are
  registered only when the registry consumer is active (`registryProvider != null`).
- **Reuse the existing pattern** — `PrometheusMeterRegistries.defaultRegistry()`
  + `PrometheusExpositionService.of(...)` at `/metrics` + snake_case
  `Gauge.builder(...).register(registry)`, exactly as `OnlinePredictionServer`
  does.

## Architecture

### `ServiceRegistryProvider` — refresh counters

Add two monotonic counters beside the existing `lastRefreshAtMs()`:

- `long refreshSuccessCount()` — incremented after each successful snapshot swap.
- `long refreshFailureCount()` — incremented in the refresh `catch` (each
  fail-static event).

These are plain `volatile long`s; they make refresh health observable without
changing fail-static behavior.

### `GatewayRegistryMetrics` (new, `com.recsys.metrics`)

A binder that registers pull-based meters against a `MeterRegistry`:

`register(MeterRegistry registry, ServiceRegistryProvider provider,
Collection<String> serviceNames, LongSupplier clockMs)`

| Meter | Type | Value |
|---|---|---|
| `gateway_registry_services_total` | gauge | number of distinct registry-mapped service names |
| `gateway_registry_services_resolved` | gauge | how many of those currently resolve from the registry (the rest are on static fallback) |
| `gateway_registry_snapshot_age_seconds` | gauge | `(clockMs − lastRefreshAtMs)/1000`, or `-1` when never refreshed |
| `gateway_registry_refresh_total` | function-counter | `provider.refreshSuccessCount()` |
| `gateway_registry_refresh_failures_total` | function-counter | `provider.refreshFailureCount()` |

Gauges sample provider state at scrape time via supplier lambdas, so there is no
push wiring and no background thread. The `LongSupplier clockMs` is injected
(defaulting to `System::currentTimeMillis` at the call site) for deterministic
tests. Registration is idempotent-safe in the sense that it is called once at
startup.

### `MicroserviceGatewayServer` — endpoint + registration

- Always build `PrometheusMeterRegistry registry =
  PrometheusMeterRegistries.defaultRegistry()` and register
  `sb.service("/metrics", PrometheusExpositionService.of(registry.getPrometheusRegistry()))`.
- When `registryProvider != null`, call
  `GatewayRegistryMetrics.register(registry, registryProvider, serviceNames,
  System::currentTimeMillis)` with the same distinct `serviceNames` list already
  computed for the provider. When the registry is disabled, `/metrics` still
  exists but carries no `gateway_registry_*` meters.

## Non-goals

- Armeria request-level metrics (`MetricCollectingService`) and JVM metric
  binders on the gateway — the endpoint is added, but wiring those is a separate
  follow-up.
- Producer/heartbeat metrics on the backend services (deferred by scope choice).
- Any change to registration semantics, the Redis schema, the `/health`
  observability from #185, or the feature flag.

## Failure behavior

- Metric reads never touch Redis — they sample in-memory provider state, so a
  Redis outage does not affect `/metrics` (and `snapshot_age_seconds` growing +
  `refresh_failures_total` rising is exactly the signal that surfaces such an
  outage).
- With the registry disabled, no registry meters are registered and behavior is
  otherwise unchanged; `/metrics` returns an exposition without
  `gateway_registry_*` series.

## Testing

- `ServiceRegistryProvider`: `refreshSuccessCount()`/`refreshFailureCount()` are
  0 initially, increment on success/failure respectively, and a failed refresh
  does not increment the success counter (nor change `lastRefreshAtMs`).
- `GatewayRegistryMetrics` against a `SimpleMeterRegistry` + a provider backed by
  a fake store + an injected fixed clock: assert `services_total`,
  `services_resolved` (partial resolution), `snapshot_age_seconds` (computed, and
  `-1` before first refresh), and the two function-counters reflect the provider.
- Gateway integration: `/metrics` returns `200` (with the flag off), confirming
  the endpoint is present and the gateway still starts.
- Full `mvn test` green.

## Expected outcome

Operators can scrape the gateway for registry health — how many upstreams are
registry-resolved vs static, snapshot freshness, and refresh success/failure —
enabling Prometheus alerts (e.g. snapshot age too high, failures climbing), with
no new dependency beyond the already-present Micrometer/Prometheus libraries and
no behavior change when the registry is off.
