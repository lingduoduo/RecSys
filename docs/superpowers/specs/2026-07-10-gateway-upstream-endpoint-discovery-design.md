# Health-Aware Upstream Discovery in the API Gateway (Option A1)

**Date:** 2026-07-10

**Scope:** API Gateway data path in `com.recsys.application.gateway`
(`GatewayRequestForwarder` and a new endpoint-group builder). No Kubernetes
manifest changes, no changes to `LlmProxyService` or `GatewayHealthService`.

## Background

The gateway is the only place one service addresses another. Today each upstream
route is a static `WebClient.builder(baseUri)` bound to a literal `host:port`
string read once at startup from a `*_SERVICE_URL` env var
(`MicroserviceRoute.defaults()`). DNS resolution happens per-connection via the
JVM resolver, whose cache is capped at 30 s so AWS Cloud Map / kube-DNS record
churn propagates. There is one retry-on-`IOException` and a per-route circuit
breaker. There is **no Armeria `EndpointGroup`**, so no client-side load
balancing and no *active* health checking — a request to a service that is down
is forwarded and only fails after the response timeout.

In EKS the backends are `ClusterIP` Services: the DNS name resolves to a single
virtual IP, and **kube-proxy** performs per-pod load balancing, with
`trafficDistribution: PreferClose` keeping traffic same-AZ (the shipped
cross-AZ-traffic-reduction work). This design must not disturb that.

## Goal

Give the gateway's data path **application-layer, health-aware upstream
discovery** on top of the existing ClusterIP DNS:

- Resolve each upstream through an Armeria endpoint group so DNS refresh is
  driven by the record TTL instead of the blunt 30 s JVM cache.
- Actively health-check each upstream and drop an unhealthy one from selection so
  the gateway **fast-fails locally** (503) instead of forwarding into a black
  hole and waiting for the timeout.

Non-goals — explicitly out of scope so the cross-AZ work is preserved:

- No headless Services and no client-side per-pod load balancing (that was
  Option A2).
- No AZ-aware endpoint selection.
- No Kubernetes manifest / ConfigMap / Cloud Map changes.
- No change to `LlmProxyService` (external, optional upstream) or to
  `GatewayHealthService`'s on-demand `/health` aggregation semantics.
- No change to routing, auth, rate-limit, retry, circuit-breaker, or timeout
  policy — those are retained as-is and composed with the endpoint group.

## Architecture

Introduce one small package-internal component and rework how
`GatewayRequestForwarder` builds its clients.

### `UpstreamEndpointGroups` (new)

A builder + holder that, given the proxy routes and config, produces:

- A `Map<String routeName, WebClient>` the forwarder uses exactly as today
  (looked up by route name in `forward`).
- The set of owned `EndpointGroup`s, exposed via `close()` so their background
  DNS-refresh and health-check schedulers are stopped on shutdown.

Endpoint groups are **deduplicated by `(host, port, healthPath)`**. The 11
default routes collapse onto ~3 backend authorities, so this yields ~3 shared
endpoint groups (each with one DNS refresher + one health checker) rather than 11
redundant ones. WebClients are cheap and remain per-route, each built over its
route's shared endpoint group.

Each shared group is:

```
DnsAddressEndpointGroup(host, port)          // TTL-bounded DNS refresh
  └─ wrapped by HealthCheckedEndpointGroup(healthPath)   // when enabled
       → WebClient(SessionProtocol.HTTP, group), round-robin selection,
         responseTimeout + the existing retry decorator
```

When health checking is disabled (config flag), the group is the plain
`DnsAddressEndpointGroup` with no active probing — used for local development
where not all backends run.

### `GatewayRequestForwarder` (modified)

- Constructor builds its clients via `UpstreamEndpointGroups` instead of the
  inline `WebClient.builder(baseUri)` map. The retry decorator and response
  timeout are applied to each per-route `WebClient` exactly as today.
- Implements `Closeable`; `close()` delegates to `UpstreamEndpointGroups.close()`.
- `forward(...)` is unchanged except that a failure to obtain a healthy endpoint
  (empty group) is caught and returned as the existing gateway 503
  (`"<route> upstream unavailable"`), matching an open-circuit response. The
  circuit breaker still records the failure.

### `MicroserviceGatewayServer` (modified)

The shutdown hook already closes the LLM `ClientFactory`; it additionally closes
the `GatewayRequestForwarder` so upstream endpoint groups are released.

## Configuration

New env vars, all with safe defaults; unknown/unset values keep current-ish
behavior:

| Env var | Default | Meaning |
|---|---|---|
| `GATEWAY_UPSTREAM_HEALTHCHECK_ENABLED` | `true` | When `false`, use a plain `DnsAddressEndpointGroup` (no active probing). |
| `GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS` | `10000` | Health-probe interval per upstream. |
| `GATEWAY_UPSTREAM_DNS_TTL_MAX_S` | `30` | Upper bound on DNS record caching, matching the existing Cloud Map TTL convention. |

The existing `networkaddress.cache.ttl = 30` JVM setting stays (harmless; it
governs any non-endpoint-group resolution and other clients).

## Failure behavior

- **Upstream down / all health checks failing:** the endpoint group has no
  healthy endpoint; selection fails fast and the gateway returns `503` with the
  existing `"<route> upstream unavailable"` body — the same shape as an open
  circuit — instead of forwarding and waiting for the response timeout. This is
  the primary improvement over today.
- **Transient DNS churn (Cloud Map re-register):** unchanged — the single
  retry-on-`IOException` still covers it, now over the endpoint group.
- **Health-check flapping:** the probe hits the ClusterIP (i.e. "is at least one
  pod behind this Service answering health"). A conservative interval avoids
  taking a healthy service out on a blip; the circuit breaker remains the
  reactive backstop on real traffic.

## Compatibility

- No manifest, ConfigMap, or Cloud Map change; kube-proxy + `PreferClose`
  continue to perform the actual same-AZ per-pod load balancing.
- Route table, prefixes, health paths, env vars, circuit breakers, rate-limit
  buckets, and health aggregation are unchanged.
- Local dev with `localhost` defaults works: DNS resolves to `127.0.0.1`; with
  health checking enabled a not-yet-running backend surfaces as `503` (today it
  is a `502` on connection refused). Set
  `GATEWAY_UPSTREAM_HEALTHCHECK_ENABLED=false` to skip probing locally.

## Testing

Integration (fake Armeria upstream with a health path):

- Healthy upstream: canonical/proxy request reaches it and passes through.
- Health path flips to `503`: within a probe cycle the route is dropped and the
  gateway returns `503` (not a hung timeout); when health recovers, requests
  succeed again.
- Health checking disabled: requests still forward (no probing), and a downed
  upstream returns an error rather than being pre-filtered.

Unit:

- `UpstreamEndpointGroups` deduplicates routes sharing `(host, port, healthPath)`
  into a single endpoint group while still exposing a `WebClient` per route name.
- `close()` releases all owned endpoint groups.

The focused gateway suite and full `mvn test` must pass. Existing gateway
integration/route-table tests continue to cover routing and forwarding
unchanged.

## Expected outcome

The gateway proactively avoids a downed upstream and fails fast with a clear
503, and upstream DNS refresh is TTL-driven rather than a fixed 30 s cache — all
while preserving ClusterIP/kube-proxy/`PreferClose` and requiring zero
infrastructure changes. This is the low-risk first step; a Redis-backed registry
(Option B) or headless client-side LB (Option A2) remain available as later,
larger changes.
