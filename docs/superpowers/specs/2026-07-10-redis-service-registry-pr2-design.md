# Redis Service Registry — PR2: Spring Producer + Consumer Observability

**Date:** 2026-07-10

**Scope:** Spring Boot `ModelApplication` self-registration (new
`com.recsys.config.ServiceRegistryConfig` + a reusable `ServiceRegistrarLifecycle`
in `com.recsys.infrastructure.registry`); registry observability in the gateway
`GatewayHealthService`; a snapshot-age accessor on `ServiceRegistryProvider`.
Builds directly on PR1 (`feat/service-registry-core`, merged).

## Background

PR1 shipped the Redis service-registry core, the gateway consumer, and
self-registration for the two Armeria backends, all behind
`SERVICE_REGISTRY_ENABLED` (default off, fail-static fallback to static route
addresses). Two pieces were deferred to PR2:

1. The **Spring Boot model service** (`ModelApplication`, port 8080) does not yet
   self-register.
2. There is **no observability** into the registry — an operator cannot see
   which upstreams the gateway is resolving from the registry versus the static
   fallback, or how fresh the snapshot is.

## Decisions

- **Reuse PR1's producer** — `ServiceRegistrar` / `ServiceRegistrar.fromEnvironment`
  are already env-gated and Spring-agnostic. PR2 only adds the Spring lifecycle
  glue to start/stop one in the model service, using the existing
  `RedisConfig.redisExecutor()` bean.
- **Observability via the existing `/health` endpoint, not new metrics.** The
  Armeria gateway has no Micrometer `MeterRegistry`; introducing one solely for a
  few registry gauges would be disproportionate. The gateway `/health` response
  already aggregates route and circuit state and is the idiomatic surface, so PR2
  adds a `registry` section there. A Micrometer-based metrics surface remains a
  possible future addition.

## Architecture

### Spring producer

- **`ServiceRegistrarLifecycle`** (new, in `com.recsys.infrastructure.registry`):
  a tiny Spring-agnostic wrapper holding a nullable `ServiceRegistrar` with
  `start()` and `close()` that are no-ops when the registrar is null (i.e. when
  the feature is disabled or the advertise env vars are unset). Reusable and unit
  testable without Spring.
- **`ServiceRegistryConfig`** (new, in `com.recsys.config`, which
  `ModelApplication` already component-scans): a `@Configuration` exposing a
  `@Bean(initMethod = "start", destroyMethod = "close")` `ServiceRegistrarLifecycle`,
  built from the injected `RedisExecutor` and
  `ServiceRegistrar.fromEnvironment(store)`. Spring starts it after refresh and
  closes it on shutdown. When the flag is off, `fromEnvironment` returns null and
  the lifecycle bean is an inert no-op — the model service acquires no registry
  behavior and its existing Redis usage is unchanged.

The model service registers under the same env contract as the Armeria backends
(`SERVICE_REGISTRY_SERVICE_NAME` = e.g. `recsys-model-serving`,
`SERVICE_REGISTRY_ADVERTISE_URL` = its advertised address).

### Snapshot age on the provider

`ServiceRegistryProvider` records `lastRefreshAtMs` (0 until the first successful
refresh) using `System.currentTimeMillis()` on each successful snapshot swap, and
exposes `long lastRefreshAtMs()`. This lets the consumer report snapshot
freshness. Fail-static behavior is unchanged: a failed refresh does not update the
timestamp.

### Consumer observability (gateway `/health`)

`GatewayHealthService` gains an optional (nullable) `ServiceRegistryProvider`
constructor argument. A 4-arg convenience constructor delegates to the new 5-arg
one with `null`, so existing callers and tests are unchanged. When the provider is
present, the health payload gains a top-level `registry` object:

```json
"registry": {
  "enabled": true,
  "snapshotAgeMs": 1234,
  "services": {
    "recsys-catalog-serving": { "source": "registry", "address": "http://recsys-catalog-serving:6010" },
    "recsys-model-serving":   { "source": "static",   "address": null }
  }
}
```

- `enabled` is `true` only when a provider is wired (registry consumer active).
- `snapshotAgeMs` = `now - lastRefreshAtMs`, or `null` if never refreshed.
- `services` lists each distinct non-null route `serviceName`: `source` is
  `registry` when `provider.resolve(name)` is present (with the resolved
  `address`), else `static` (address `null`, meaning the gateway is using the
  route's static base URI).

When no provider is wired (registry disabled), the `registry` object is omitted
entirely, so the health response is byte-for-byte unchanged from today.

`MicroserviceGatewayServer` passes the `registryProvider` (already built in PR1,
nullable) into `GatewayHealthService`.

## Non-goals

- Micrometer/Prometheus metrics for the registry (possible future work).
- Changing registration semantics, the Redis schema, the feature flag, or any
  fallback behavior from PR1.
- Registering any service other than the model service (Armeria backends already
  register in PR1).
- Kubernetes manifest changes.

## Testing

- `ServiceRegistrarLifecycle`: `start`/`close` delegate to a non-null registrar;
  both are no-ops (no NPE) when the registrar is null.
- `ServiceRegistryConfig`: builds a lifecycle bean; with the flag off the wrapped
  registrar is null (asserted via the lifecycle being a safe no-op). A focused
  Spring context test may verify the bean is present; if that is heavy, a plain
  unit test of the config method suffices.
- `ServiceRegistryProvider`: `lastRefreshAtMs()` is 0 before any refresh and > 0
  after a successful refresh; a failing refresh leaves it unchanged.
- `GatewayHealthService`: with a provider that resolves one of two services, the
  `/health` body's `registry.services` marks that service `registry` (with
  address) and the other `static`; with no provider, the response contains no
  `registry` key. Snapshot age is present when a provider is wired.
- Full `mvn test` green; the model service still starts with the flag off.

## Expected outcome

The Spring model service participates in the registry on the same opt-in contract
as the other backends, and operators can see — via the gateway `/health`
endpoint — exactly which upstreams are registry-resolved versus static and how
fresh the registry snapshot is, all with no new dependencies and no change when
the feature is off.
