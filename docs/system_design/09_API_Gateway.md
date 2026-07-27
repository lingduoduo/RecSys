# API Gateway in Recsys-Backend-Service

An investigation of the single public edge — `MicroserviceGatewayServer` (Armeria,
port 8010) — that fronts the three backend services: how it routes and prefix-strips, how it
is the trust boundary that authenticates callers and strips their credentials before
proxying, how it aggregates downstream health, and how the cross-cutting concerns
(circuit breaking, rate limiting, upstream discovery, the LLM proxy) compose into
one request pipeline. Several of those concerns have their own deep-dives; this doc
is the map that ties them together.

## The big picture — one edge, one pipeline

The gateway implements the **API Gateway pattern**: a single edge that concentrates
cross-cutting concerns so backends stay simple and clients learn just one hostname
and port. The tradeoff is one shared choke point — mitigated by health-checked
upstreams and per-route circuit breakers — in exchange for that simplicity. The three
backend services sit behind it.

A request traverses a deliberate pipeline. Some stages are Armeria server decorators
(applied before dispatch); the rest run inside the proxy per request:

| Stage | Where | Effect |
|---|---|---|
| `/metrics`, `/health` | server routes | Prometheus + health aggregation, before auth |
| **Origin secret** | server decorator | Reject non-CDN traffic `403` (only when enabled) |
| Route match | `MicroserviceRouteTable` | Longest-prefix; LLM + `/api/recommend` win over catch-all |
| **Auth** | inside `GatewayProxyService.serve` | Edge auth → principal, or `401` |
| **Rate limit** | inside `GatewayRequestForwarder.forward` | Per `(route, principal)` → `429` |
| **Circuit breaker** | inside `forward` | Open → `503` fast-fail |
| Header rewrite | `buildUpstreamHeaders` | Strip credentials, inject identity |
| Proxy | `forward` | Prefix-strip, pick upstream, pipe, map errors |

The bootstrap wiring, decorator order, and shutdown hook live in
[`MicroserviceGatewayServer`](../../src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java)
(LLM routes and the canonical `/api/recommend` are registered *before* the catch-all
`prefix:/` so Armeria's more-specific match wins). Key knobs: `GATEWAY_PORT` (8010),
`GATEWAY_TIMEOUT_MS` (3000), and `networkaddress.cache.ttl=30` for Cloud Map
blue/green.

## 1. Routing and prefix-strip

The route table
([`MicroserviceRoute`](../../src/main/java/com/recsys/application/gateway/MicroserviceRoute.java))
is a list of `(name, prefix, envVar, baseUri, healthPath, serviceName)` records;
[`MicroserviceRouteTable`](../../src/main/java/com/recsys/application/gateway/MicroserviceRouteTable.java)
is a **longest-prefix router** (exact-prefix map first, then routes sorted
longest-first). Registered routes:

| Gateway prefix | Backend | Notes |
|---|---:|---|
| `/api/recommend/{embedding,model,online,sequential}` | 6010/8080/7010/8080 | Strategy-specific recommendation routes |
| `/api/users`, `/api/movies` | 6010 | User / movie metadata |
| `/api/features` | 7010 | Online feature snapshot |
| `/api/knowledge` | 8080 | Knowledge service |
| `/api/catalog`, `/api/model`, `/api/online` | 6010/8080/7010 | Deprecated back-compat aliases |
| `/api/llm`, `/api/explanations` | (LLM) | Opt-in, registered only when the env var is set |

The **canonical** entry point is `POST /api/recommend`: it takes an optional JSON
`strategy` (`embedding` / `model` / `online` / `sequential`, default `model`),
selects the matching backend, and **removes the `strategy` selector** before
forwarding so the upstream receives its normal schema.

Prefix-strip is `MicroserviceRoute.rewrite`: it validates the prefix match, takes
`suffix = path.substring(prefix.length())` (blank → `/`), joins it onto the route's
`baseUri`, and preserves the raw query string. `matchesPrefix` enforces segment
boundaries (`path == prefix || startsWith(prefix + "/")`), so `/api/usersettings`
does **not** match `/api/users`.

[`GatewayRequestForwarder.forward`](../../src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java)
does the actual proxying and maps failures to a clean contract:

| Condition | Status |
|---|---:|
| Rate limit exceeded | `429` + `Retry-After` / `x-ratelimit-*` |
| Circuit open | `503` "circuit open — upstream unavailable" |
| No healthy endpoint (`EmptyEndpointGroupException`) | `503` |
| Upstream 5xx | passthrough (records CB failure) |
| Upstream unreachable / exception | `502` "upstream unreachable" |

A single retry (`maxTotalAttempts=2`, fixed 50 ms backoff) covers transient
`IOException` — but **not** `SocketTimeoutException` — to ride out Cloud Map
deregistration windows. `GatewayProxyService.gatewayError` emits `{"error":...}` JSON
with `Cache-Control: no-store` so CloudFront never caches an error.

### API versioning and deprecation

The public surface is **unversioned**. Every prefix in `MicroserviceRoute.defaults()`
is `/api/<resource>` with no version segment, and there is no header-based scheme
either — no `Accept-Version`, no `X-API-Version`, and no versioned media type
(every `produces` is plain `application/json`). Version numbers exist only on the
*internal* hop after prefix-strip, where three conventions coexist:

| Convention | Service | Examples |
|---|---:|---|
| Root `/v1/…` `/v2/…` | 6010, 7010 | `/v1/models/recmodel:predict`, `/v1/catalog/movies`, `/v2/recommend` |
| Spring `/api/v1/…` | 8080 | `/api/v1/recommend`, `/api/v1/auth`, `/api/v1/model/versions` |
| Spring root `/v2/…` | 8080 | `/v2/recommend`, `/v2/sequential/recommend` |
| Unversioned | 6010, 7010 | `/getrecommendation`, `/similar`, `/item`, `/online/features` |

Three consequences follow from keeping the version *behind* the edge:

- **`/v2` denotes a pipeline variant, not an API generation.** On 6010,
  `RecommendationService.V1` serves `/getrecommendation` while `V2` serves
  `/v2/recommend` — but V2 is the orchestrator pipeline (recall → rank → hydrate →
  paginate with a cursor), not a compatible successor to V1. The same split exists on
  7010 and 8080. At the edge, selection is done by the JSON `strategy` field instead,
  so content-based routing occupies the slot a version selector would.
- **A version segment lands mid-path whenever it is exposed.** Prefix-strip passes the
  suffix through verbatim, so the model service's version-management API is reachable
  only as `/api/model/api/v1/model/versions`. Paths with no matching prefix —
  `/api/v1/auth/login` among them — fall through to the catch-all and get
  `404 "no route found"`.
- **Deprecation is documentation-only.** `/api/catalog`, `/api/model`, and
  `/api/online` are marked back-compat aliases in the table above, but no
  `Deprecation` or `Sunset` response header is emitted anywhere. That was a deliberate
  deferral: the
  [canonical entry-point design](../superpowers/specs/2026-07-10-canonical-recommendation-gateway-entry-point-design.md)
  declined to add one because doing it consistently "would require a separate
  compatibility policy and removal schedule". That policy has not been written.

There is no OpenAPI/springdoc artifact and no cross-version contract test, so nothing
machine-readable defines what `v1` is. If versioning ever moves to the edge, the
URL-versus-header choice is constrained by the CDN cache key — see
[12_CDNS §1](12_CDNS.md#1-what-is-cached-and-what-isnt) — and by the exact-path
`GATEWAY_PUBLIC_PATHS` and `PROTECTED_PREFIXES` lists, which would each need a new
entry per version.

Versioning *is* applied rigorously to non-HTTP contracts: the signed recommendation
cursor, whose payload leads with a format version and whose predecessor `v2:` tokens
are accepted only behind a compatibility flag
([19_Pagination §4](19_Pagination.md#4-implemented-recommendation-optimization)), the
`sr:g{version}:` shard generations with a bounded dual-read window
([14_Partitioning](14_Partitioning.md#versioned-topology-and-online-reshard)), and
model-artifact versions keyed into the result caches
([02_Caching §3](02_Caching.md#3-recommendationcache--result-and-cold-start-caching)).

## 2. Identity propagation and credential stripping

The gateway is the **trust boundary**, and
[`buildUpstreamHeaders`](../../src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java)
is where that boundary is enforced on every proxied request:

- **Credentials are consumed, never forwarded** — `Authorization`, `x-api-key`, and
  the `x-origin-secret` header are stripped, so upstreams never see raw credentials.
  Hop-by-hop headers are dropped too.
- **Client-supplied `x-authenticated-*` headers are stripped** (case-insensitive
  16-char prefix match) — an anti-spoofing defense, since backends may trust those
  headers *only* because the gateway is their sole source.
- **The gateway re-injects the authenticated identity** as
  [`GatewayPrincipal.identityHeaders()`](../../src/main/java/com/recsys/application/gateway/GatewayPrincipal.java):
  `x-authenticated-subject` / `x-authenticated-client-id` / `x-authenticated-token-use`
  (only when non-blank), plus `x-gateway-service: recsys-api-gateway` and the
  `x-forwarded-*` set. The principal comes from a Cognito JWT (claims) or an API-key
  caller (SHA-256 short-hash id), or `anonymous()` for public/disabled paths.

This holds identically for the service proxy and the LLM proxy. Designs:
[credential stripping](../superpowers/specs/2026-07-02-gateway-credential-stripping-design.md),
[principal propagation](../superpowers/specs/2026-07-02-gateway-principal-propagation-design.md).

## 3. Authentication

Edge authentication is the gateway's slice of the broader AuthN/AuthZ concern; the
whole picture — every credential, the operator-token tier, and the service-local
tokens on 8080 — is in the [AuthN/AuthZ investigation](20_AuthN_AuthZ.md).
[`GatewayAuthenticator`](../../src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java)
accepts **either** a static API key (`GATEWAY_API_KEYS`, constant-time compare, via
`X-API-Key` or `Authorization: Bearer`) **or** a Cognito RS256 JWT
(`GATEWAY_COGNITO_ISSUER` / `_AUDIENCE`; a dependency-free verifier that fetches JWKS,
caches keys 5 min, validates `iss`/`aud`/`exp`/`token_use`, and serves last-good keys
stale on a transient JWKS failure). A bearer token is tried as an API key first, then
a JWT; matching neither yields `401 WWW-Authenticate: Bearer`.

Two guards make the open/closed posture safe: `GatewayAuthenticator.fromEnvironment`
**fails closed** — it refuses to start wide-open unless `GATEWAY_ALLOW_ANONYMOUS=true`
explicitly opts in — and `PROTECTED_PREFIXES` (`/api/catalog/user`, `/api/users`) can
never be made public even if listed in `GATEWAY_PUBLIC_PATHS`
([never-public design](../superpowers/specs/2026-07-19-gateway-never-public-user-paths-design.md)).
Public-path matching and the CloudFront origin secret (`GatewayOriginSecret`) are
covered from the edge-caching angle in the
[CDN Edge investigation](12_CDNS.md#3-origin-lockdown--proving-a-request-came-from-our-distribution);
operational auth setup is in [gateway-auth.md](../runbooks/gateway-auth.md).

## 4. Rate limiting

[`GatewayRateLimiter`](../../src/main/java/com/recsys/ratelimit/GatewayRateLimiter.java) is
a token bucket keyed **per `(route, principal)`**, so one noisy caller can't exhaust
another's budget. Buckets refill at `GATEWAY_RATE_LIMIT_RPS` with a
`GATEWAY_RATE_LIMIT_BURST` burst; excess requests get `429`. The principal is the
authenticated identity (Cognito `sub` or a hashed API-key id; `anonymous` when auth
is off). Buckets live in a bounded Caffeine cache (`GATEWAY_RL_MAX_PRINCIPALS`,
default 100000) so a flood of distinct identities can't grow memory without bound.
Per-route overrides use the route name upper-snake-cased:
`model-inference` → `GATEWAY_RATE_LIMIT_MODEL_INFERENCE_RPS`. This is the gateway's
per-instance limiter; how it fits alongside the model per-user, LLM token-budget, and
global Redis limiters is covered in the
[Rate Limiting investigation](08_Rate_Limits.md).

## 5. Resilience the gateway applies (cross-links)

Three edge concerns are documented in depth elsewhere; the gateway is where they
attach:

- **Per-route circuit breakers** — `RouteCircuitBreaker`, one per route, sharing the
  state machine that also backs the LLM proxy and Redis rate limiter. See
  [Fault Tolerance §1](18_Fault_Tolerance.md#1-request-tier-resilience--circuit-breakers-bulkheads-fault-injection).
- **Health-checked upstreams + registry resolution** — `UpstreamEndpointGroups` drop
  a down backend so a request fast-fails `503` instead of hanging, and the opt-in
  registry resolves upstream addresses dynamically with static fallback. See the
  [Service Discovery investigation](11_Service_Discovery.md).
- **The LLM proxy** — `LlmProxyService` proxies LLM routes on a dedicated client with
  token budgets, SHA-256 response caching, and SSE streaming passthrough. See the
  [SSE Streaming investigation](16_SSE_Streaming.md).

## 6. Health aggregation

`GET /health`
([`GatewayHealthService`](../../src/main/java/com/recsys/application/gateway/GatewayHealthService.java))
pings every registered downstream **in parallel** (latency is max, not sum) and
returns two views:

- a deduped **`ports` rollup** — one entry per distinct backend port, plus the
  gateway's own `8010` as a self-check; a port is `UP` only when *every* route
  targeting it is healthy;
- the full per-route **`services`** detail (status, prefix, health URL, status code,
  latency, `circuitState`, error).

The overall `status` is `DEGRADED` (HTTP `503`) whenever any backend is down — the
gateway's self-check never masks a failing backend. When the service registry is
enabled the response also carries a `registry` section (resolution source + snapshot
age). `/health` is a public path (no auth), so probes always reach it.

## 7. Metrics

The gateway exposes Prometheus at `GET /metrics` (registered before auth, via
`PrometheusExpositionService` on Armeria's default registry). Origin-secret
rejections (`gateway_origin_secret_rejected_total`) and, when the registry is
enabled, the `gateway_registry_*` meters
([`GatewayRegistryMetrics`](../../src/main/java/com/recsys/metrics/GatewayRegistryMetrics.java))
publish through the same registry.

## 8. Testing

- **Routing** — `GatewayRouteTableTest` (canonical strategy mapping, no duplicate
  prefixes, back-compat aliases kept, rewrite targets) and `MicroserviceRouteTest`
  (longest-prefix match, no partial-segment match, prefix-strip + query preservation,
  `healthUri`, service-name assignment).
- **Trust boundary** — `GatewayRequestForwarderTest` (strips spoofed identity and
  injects the principal, strips gateway-consumed credentials, strips the origin
  secret).
- **End-to-end** — `GatewayServerIntegrationTest` on a live server (health 200 + port
  rollup + gateway self, proxies to upstream, canonical recommend default/explicit/
  non-POST, legacy alias proxies, unmatched → 404, circuit-open → 503, auth rejects
  no key) and `GatewayMetricsEndpointTest`.

## Sharp edges — notes

1. **The edge is a single choke point — on purpose.** Everything flows through 8010;
   the mitigations are per-route breakers, health-checked upstreams, and the parallel
   health check. A gateway outage is a full outage, so it runs `minReplicas ≥ 2`
   behind the ALB.
2. **Backends trust `x-authenticated-*` only because the gateway is their sole
   source.** The anti-spoof strip is load-bearing — a backend exposed directly
   (bypassing the gateway) would trust attacker-supplied identity headers.
3. **Auth and rate limit run inside the proxy, not as server decorators.** Only the
   origin-secret check and `/health` `/metrics` are true decorators; auth/rate-limit
   are per-request in `serve`/`forward`, which is why `/health` and `/metrics` are
   reachable without auth.
4. **Route precedence is registration order + longest prefix.** LLM routes and
   `/api/recommend` are registered before the catch-all so they win; adding a broad
   new prefix can accidentally shadow a narrower one if boundaries aren't respected.
5. **`502` vs `503` is a real distinction.** `503` means the gateway declined
   (circuit open / no healthy endpoint / rate limit); `502` means it tried and the
   upstream was unreachable. They point at different problems during an incident.
6. **A higher `/v2` does not mean more protection.** The canonical `POST /api/recommend`
   with the default `model` strategy forwards to `/v2/recommend` on 8080
   (`RecommendationV2Controller`), which is a thin `pipeline.recommend(query)`
   passthrough. The `/api/v1/recommend` controller it bypasses is the one carrying the
   per-user `ModelRateLimiter`, the submit-token CSRF check, A/B exposure logging, and
   the degradation headers. Only `LoginInterceptor` (bearer parsing for `@NeedLogin`)
   is global on 8080, so the edge's own limiter and breaker are the sole request-tier
   controls on the canonical path.
