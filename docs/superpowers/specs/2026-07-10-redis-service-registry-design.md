# Redis-Backed Service Registry — PR1: Core + Armeria Producers + Gateway Consumer

**Date:** 2026-07-10

**Scope:** New `com.recsys.infrastructure.registry` package; producer wiring in
`RecSysServer` and `OnlinePredictionServer`; consumer wiring in the API gateway
(`MicroserviceGatewayServer` / `GatewayRequestForwarder`); an optional
`serviceName` on `MicroserviceRoute`. Feature-flagged and off by default.

## Background

Service discovery today is infrastructure-delegated: the gateway resolves each
upstream from a `*_SERVICE_URL` env var read once at startup, and Kubernetes
(ClusterIP + kube-DNS, with `PreferClose`) does the per-pod load balancing. PR
#183 added application-layer health-aware selection (Armeria endpoint groups) on
top of that static address. There is no application-level registry: adding,
moving, or renaming a backend requires a redeploy, and nothing works outside
Kubernetes.

This change introduces an **application-level, Redis-backed service registry**
(Option B from the service-discovery investigation) as an **opt-in overlay**.
When enabled, backends self-register their advertised address with a heartbeat,
and the gateway resolves upstreams from the registry — falling back to the
existing static address whenever a service is unregistered or Redis is
unavailable. The proven `ShardTopologyStore` / `ShardTopologyProvider` pattern
(authoritative Redis state → background poll → atomic `volatile` swap →
fail-static) is the template.

## Decisions (from brainstorming)

- **Service-level registration**, not pod-level: instances register their
  advertised *service* address (e.g. the ClusterIP DNS name + port), so
  kube-proxy and `PreferClose` keep doing per-pod, same-AZ load balancing and the
  cross-AZ-traffic work is untouched. (Pod-level LB would be Option A2.)
- **Feature-flagged overlay** with static fallback, not a replacement: safe to
  ship dark; existing behavior is byte-for-byte unchanged when the flag is off.
- **Two PRs.** PR1 (this spec): registry core, the two Armeria backends register,
  gateway consumes. PR2: the Spring Boot `ModelApplication` registers; docs /
  observability polish.

## Architecture

New package `com.recsys.infrastructure.registry`:

### `ServiceRegistryStore`
Redis adapter over the existing `RedisExecutor`. Key per service:
`svc:registry:<serviceName>` → a small JSON document
`{address, metadataJson, updatedAtMs}`.

- `register(serviceName, address, ttlMs)` → `SET key json PX ttlMs` (idempotent;
  replicas of one service write the same address, last-writer-wins).
- `deregister(serviceName)` → `DEL key` (best-effort on shutdown).
- `lookup(Collection<String> serviceNames)` → **MGET** over the exact keys →
  `Map<serviceName, address>` for present entries. Bounded by the number of
  known services; no `SCAN`/`KEYS`.

TTL expiry is the liveness signal — a service whose replicas all stop renewing
disappears from `lookup` automatically.

### `ServiceRegistrar` (producer)
A reusable heartbeat writer. `start()` performs an initial `register(...)` then
schedules a daemon thread (`svc-registry-heartbeat`) that re-registers every
`heartbeatMs`. `close()` stops the scheduler and best-effort `deregister(...)`s.
`ttlMs` is set to ≥ 3× `heartbeatMs` so a single missed beat does not drop the
entry. Registration failures are logged and non-fatal (never break serving).

### `ServiceRegistryProvider` (consumer)
Holds a `volatile` immutable snapshot (`Map<serviceName, address>`), refreshed by
a daemon thread (`svc-registry-refresh`) every `refreshMs` via
`store.lookup(knownServiceNames)`. On any refresh error the last-good snapshot is
retained — **fail-static**, so Redis problems never break the request path.
`resolve(serviceName)` returns `Optional<String>` (the registered address, or
empty). `start()`/`stop()` mirror `ShardTopologyProvider`.

## Route ↔ service mapping

`MicroserviceRoute` gains a nullable `serviceName` component. To avoid churn
across the 7 files that use the current 5-argument constructor, a 5-arg
convenience constructor delegates to the 6-arg canonical constructor with
`serviceName = null`. `MicroserviceRoute.defaults()` sets `serviceName` per route:

| serviceName | routes |
|---|---|
| `recsys-catalog-serving` | `embed-recall`, `catalog`, `user-profile`, `movie-metadata` |
| `recsys-model-serving` | `model-inference`, `model`, `knowledge`, `sequential` |
| `recsys-online-serving` | `online-blend`, `online`, `feature` |
| (none) | `llm`, `llm-explanation` — always static |

These names match the ConfigMap ClusterIP service names. A route with a null
`serviceName` is never registry-resolved.

## Gateway consumer integration

A new `RegistryBackedUpstreams` composes the registry with the PR #183
`UpstreamEndpointGroups`:

- It owns a current `UpstreamEndpointGroups` and, per route, resolves the
  effective base URI as `provider.resolve(route.serviceName()).orElse(route.baseUri())`.
- On each provider refresh, if the resolved address set changed, it **rebuilds**
  `UpstreamEndpointGroups` from routes whose `baseUri` has been overlaid with the
  registered addresses, atomically swaps the reference, and closes the previous
  one (the `ShardTopologyProvider` swap pattern applied to endpoint groups).
- `clientFor(routeName)` delegates to the current `UpstreamEndpointGroups`.
- `close()` stops the provider and closes the current groups.

`GatewayRequestForwarder` uses `RegistryBackedUpstreams` when the registry is
enabled, and the plain static `UpstreamEndpointGroups` (today's path, no Redis)
when it is not. Health-checked selection, retry, circuit breaking, rate limiting,
timeouts, and the empty-group→503 mapping are unchanged and apply to whichever
address is in effect.

The gateway builds a `RedisExecutor` (`LettuceClientFactory.routingFromEnv()`)
**only when the flag is on**; when off, the gateway acquires no Redis dependency
and behaves exactly as today.

## Configuration

New env vars, all with safe defaults; the feature is off unless explicitly
enabled:

| Env var | Default | Meaning |
|---|---|---|
| `SERVICE_REGISTRY_ENABLED` | `false` | Master switch for both producer and consumer paths. |
| `SERVICE_REGISTRY_HEARTBEAT_MS` | `10000` | Producer re-register interval. |
| `SERVICE_REGISTRY_TTL_MS` | `30000` | Entry TTL (≥ 3× heartbeat). |
| `SERVICE_REGISTRY_REFRESH_MS` | `10000` | Gateway snapshot poll interval. |
| `SERVICE_REGISTRY_SERVICE_NAME` | (per service) | The name a backend registers under. |
| `SERVICE_REGISTRY_ADVERTISE_URL` | (per service) | The address a backend advertises. |

A producer with the flag on but no `SERVICE_REGISTRY_SERVICE_NAME` /
`SERVICE_REGISTRY_ADVERTISE_URL` logs a warning and does not register (it cannot
invent its own routable address).

## Failure behavior

- **Redis unreachable (consumer):** the provider keeps its last-good snapshot (or
  an empty one at startup); `resolve` returns empty for missing services →
  gateway uses the static route address. The gateway keeps serving.
- **Redis unreachable (producer):** heartbeat writes fail and are logged; the
  entry eventually expires and consumers fall back to static. Serving is
  unaffected.
- **Service unregistered / TTL expired:** `resolve` returns empty → static
  fallback.
- **Flag off:** no registry code path runs; no Redis connection is opened in the
  gateway.

## Testing

Unit (fake `RedisExecutor`, injected clock/scheduler where needed):

- `ServiceRegistryStore`: `register` issues `SET` with the TTL; `lookup` MGETs the
  exact keys and maps present entries; absent keys are omitted; `deregister`
  DELs.
- `ServiceRegistrar`: initial register on `start`; re-registers on the heartbeat;
  `close` stops the scheduler and best-effort deregisters; registration failure
  is swallowed.
- `ServiceRegistryProvider`: refresh swaps the snapshot; a throwing store keeps
  the last-good snapshot (fail-static); `resolve` reflects the current snapshot.
- `MicroserviceRoute`: the 5-arg constructor yields `serviceName == null`;
  `defaults()` assigns the documented service names.

Integration (real Armeria upstream + fake registry provider):

- Flag on with a registry entry: the route resolves to the registered address and
  the request reaches that upstream.
- No entry / provider returns empty: the route falls back to the static base URI.
- A resolved-address change triggers a rebuild and subsequent requests use the
  new address; the old endpoint groups are closed.

The focused gateway/registry suites and full `mvn test` must pass.

## Expected outcome

An opt-in, Redis-backed service registry that lets the gateway discover backend
addresses dynamically (and work outside Kubernetes) while preserving
kube-proxy/`PreferClose` LB, the PR #183 health-aware selection, and — with the
flag off — today's exact behavior with no new dependencies. PR2 adds the Spring
model-service producer and observability.
