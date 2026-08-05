# Gateway proxy route policy

Decide what the gateway is willing to proxy at all. Today it forwards every path under a route
prefix verbatim, so a backend's telemetry and its control-plane writes are reachable by any
authenticated caller.

## The gap

`MicroserviceRoute.rewrite` strips the matched prefix and forwards the remaining suffix
unchanged. There is no allow-list, no denylist, and no per-path privilege. Every backend route is
therefore reachable through *every* gateway prefix that targets its service:

| Backend | Reachable through |
|---|---|
| 6010 | `/api/catalog`, `/api/users`, `/api/movies` |
| 7010 | `/api/online`, `/api/features` |
| 8080 | `/api/model`, `/api/knowledge`, `/api/recommend/model`, `/api/recommend/sequential` |

That is roughly 45 enumerated backend routes plus Spring's `/actuator/{health,info,prometheus}`,
which no test scans. Thirteen are user-scoped and checked since PR #275; the rest are reachable by
anyone holding an API key. They fall into three groups:

- **Telemetry.** `/metrics` on 6010 and 7010, `/actuator/prometheus`, and 8080's
  `/health/{jvm,gc,cache,metrics,load,ab-tests}`. No user or item identifiers — that was checked
  during the leakage audit — but real reconnaissance value: capacity, cache hit rates, model
  variant names, A/B experiment names.
- **Control-plane writes.** `/setembedding` (overwrite item embeddings) and
  `/api/v1/model/versions/{activate,rollback,preload}` (swap the serving model). This is the half
  of `20_AuthN_AuthZ` sharp edge 1 that the user-scope work did not touch, and it outranks the
  telemetry.
- **Session.** `/api/v1/auth/{login,logout}`.

The audit ranked this third by severity and called it "moderate, not high" on the strength of the
telemetry being unidentifying. That reasoning holds for the telemetry and misses the writes.

## Scope

In scope: every backend route reachable through a gateway route prefix, classified and enforced.

Out of scope:

- Direct-to-pod access. The backends still authenticate nobody (`20_AuthN_AuthZ` sharp edge 6);
  this design controls the *proxied* path only. A misapplied NetworkPolicy still exposes
  everything, and this work does not change that.
- `GATEWAY_PUBLIC_PATHS`. It answers a different question — who may call — and is untouched.
- Per-backend admin guards on 6010 and 8080. 7010 has one; giving the other two their own would be
  real defense in depth and a much larger change. The gateway check is the single choke point here.

## One table, four classes

`UserScopedRoutes` becomes `BackendRoutePolicy`, keyed on the same `(serviceName, backendPath)`
pair and carrying one classification per backend route:

```java
enum Access { NO_PROXY, OPERATOR, USER_SCOPED, AUTHENTICATED }
record RoutePolicy(Access access, UserIdSource userIdSource)  // source non-null iff USER_SCOPED
```

Absorbing the existing table rather than adding a second one is the whole point. The new
classification is keyed on exactly the pair `UserScopedRoutes` already uses, and two tables with an
identical key that must agree is the failure mode the final review of PR #275 caught between
`PROTECTED_PREFIXES` and `UserScopedRoutes`. Repeating it deliberately, days later, would be
indefensible.

| Class | Routes | Effect |
|---|---|---|
| `NO_PROXY` | `/metrics` (6010, 7010), `/actuator/*` (8080), 8080's `/health/{jvm,gc,cache,metrics,load,ab-tests}`, and every backend `/health`, `/health/live`, `/health/ready`, `/health/load` | 404 at the gateway |
| `OPERATOR` | `/setembedding`, `/api/v1/model/versions` and its `activate`/`rollback`/`preload`, `/online/ops` | `X-Admin-Token` required |
| `USER_SCOPED` | the thirteen declared in PR #275 | existing check, unchanged |
| `AUTHENTICATED` | `/item`, `/movie`, `/similar`, `/v1/catalog/movies`, `/api/v1/token`, `/api/v1/knowledge-bases*`, `/api/v1/auth/*`, `/shards/` | today's behavior |

Classifying the backend health endpoints `NO_PROXY` does not affect any probe. The gateway's
upstream health checking dials `route.baseUri() + healthPath` directly through
`UpstreamEndpointGroups`, never through its own proxy path, and ALB and kubelet probes hit the pods
directly. The four `ServiceMonitor`s likewise scrape the backends, not the gateway. `NO_PROXY`
governs only what a *client* may reach by asking the gateway for it.

`/shards/` is `AUTHENTICATED` rather than `OPERATOR` because the prefix mixes tiers: `POST
/shards/topology` and `GET /shards/shard` are operator surfaces, while `GET /shards/device` and
`POST /shards/records` are ordinary data paths. `ShardedRecordService` dispatches on `ctx.path()`
internally and already applies `AdminTokenGuard` to the first two, so the backend keeps that
distinction; classifying the whole prefix `OPERATOR` at the gateway would break the data paths.

**An unclassified path is denied.** The allow-list default is what makes this close a class rather
than a list of instances: a new `/actuator` endpoint or diagnostic route added to a backend is
unreachable through the gateway the day it ships, instead of exposed the day it ships. The coverage
test already forces every backend route to be classified, so the maintenance burden exists either
way — this only decides which way an unclassified route fails.

Matching is exact, with two necessary exceptions. `/actuator` is driven by
`MANAGEMENT_ENDPOINTS_EXPOSURE` rather than source, and `/shards/` is registered as a single
Armeria `pathPrefix`; neither can be enumerated. They live in a separate two-entry prefix table,
consulted only after an exact miss, with a test asserting no prefix entry shadows a declared exact
path. Exact-first keeps the `/api/catalog` trap of §3 closed while admitting the two cases that
genuinely cannot be listed.

## Enforcement

One block in `GatewayRequestForwarder.forward`, beside `authorizeUserScope` — after the
rate-limit gate, before the circuit-breaker permit is acquired, for the reasons §10 already
records:

```
service = effectiveServiceName(route, MicroserviceRoute.defaults())
if service != null:
    policy = BackendRoutePolicy.lookup(service, pathWithoutQuery(targetPath))
    if policy == null || policy.access == NO_PROXY  -> 404 "no route found"
    if policy.access == OPERATOR                    -> X-Admin-Token or 403
    if policy.access == USER_SCOPED                 -> existing user-scope check
```

**404, and byte-identical to the existing unmatched-route response.** A path that exists but is
withheld must not be distinguishable from one that was never routed; a distinct status or body
would turn the allow-list into an enumeration oracle.

**The operator check is tier-independent**, unlike user scope. Service-tier callers are exempt from
user scope because the trust model says they are backends acting for many users. No such argument
covers swapping the serving model: an API key alone must not be sufficient, which is the entire
point of the class.

**Fail closed when `SHARD_ADMIN_TOKEN` is unset** — 403, the rule `AdminTokenGuard` already applies
on 7010. That backend guard stays exactly as it is; the gateway check is an additional layer, not a
replacement, and `/online/ops` is therefore covered twice.

`service != null` is what keeps LLM routes out of the allow-list: their upstream is not ours to
classify, and `UserScopedRoutes.effectiveServiceName` already returns null for a genuine LLM
authority. Reusing that resolver rather than inventing a second exemption also means an
`LLM_SERVICE_URL` mistakenly pointed at a backend is subject to the allow-list, not excused by it.

`AdminTokenGuard` currently lives in `com.recsys.api.online`. Importing it from the gateway would
cross a layer boundary the package map otherwise keeps clean, so it moves to a neutral location as
part of this work rather than being imported across.

## The coverage test gets stronger and smaller

`UserScopedRouteCoverageTest` becomes `BackendRouteCoverageTest`: every scanned `(service, path)`
must carry a policy. The `NOT_USER_SCOPED` map — thirty-two hand-written reason strings —
disappears, because each of those routes now has a real classification instead. Less prose to
maintain, and more meaning enforced: a reason string was advisory, a class is executable.

Everything else is kept: the recursive `Files.walk` scan, the sweep asserting no route registration
lives outside the scanned locations, the per-service floors, and the assertion that every route
reaching a backend declares a registry service name. One check is added — that no prefix entry
shadows a declared exact path.

Limitation worth stating: `/actuator` cannot be scanned, because Spring's exposure is configuration
rather than source. It is declared `NO_PROXY` in the prefix table and nothing cross-checks that
declaration against reality. Better than today, where it is neither scanned nor declared, but not
the guarantee the enumerated routes get.

## Testing

Table-lookup units: exact beats prefix, prefix boundary matching, unknown service, unknown path.

Forwarder tests, one per class:

1. Unclassified path — 404, body identical to the unmatched-route response.
2. `NO_PROXY` — 404, same body.
3. `OPERATOR` with no token, with a wrong token, and with `SHARD_ADMIN_TOKEN` unset — 403 in all
   three.
4. `OPERATOR` with the correct token — forwarded.
5. `OPERATOR` reached by a service-tier caller with no token — 403. This is the one that pins
   tier-independence.
6. `USER_SCOPED` and `AUTHENTICATED` — unchanged from today.
7. A route resolving to no known backend (LLM) — unaffected by the allow-list.

All of it goes into the `-Presilience` profile, or it does not gate PRs.

## Rollout

Unlike the user-scope work, this changes behavior on day one. Two things break by design:
telemetry paths through the gateway start returning 404, and control-plane writes with only an API
key start returning 403.

Nothing in `scripts/`, `docs/`, or the test suite calls a backend ops path through the gateway —
checked. External callers cannot be ruled out the same way.

**The one operational risk is the token.** `SHARD_ADMIN_TOKEN` is wired today only into the online
deployment, from the `recsys-online-admin` Secret with `optional: true`. The gateway needs it too,
and if it is missing every operator path fails closed. The secret must therefore reach the gateway
*before or with* the image, not after — a manifest change that has to land first.

If the token is unset in a region, the break-glass for a bad model rollback is a direct pod
connection, because the backends authenticate nobody. That is sharp edge 6 acting as a safety net.
It is documented here because it is true, not because it is a property to rely on.

## Documentation

- `docs/system_design/20_AuthN_AuthZ.md`: a new appended section on what the gateway will proxy,
  with no `##` renumbering. Sharp edge 1 is rewritten a second time — the control-plane half is now
  closed for *proxied* access and remains open on a direct pod connection.
- `.claude/CLAUDE.md`: the gateway now enforces an operator tier and reads `SHARD_ADMIN_TOKEN`.
- `docs/runbooks/gateway-auth.md`: how to call an operator path, and what a 403 there means.
