# Gateway user-scope authorization

Close the read-any-user gap: today any authenticated caller may request data for any
`userId`, because `userId` is an ordinary request field and no component compares it to the
authenticated principal.

## The gap

`GatewayRequestForwarder` strips client-supplied `x-authenticated-*` headers and injects the
identity the gateway derived, so backends can trust `x-authenticated-subject` absolutely. They
do not read it. A grep for the header outside the gateway package returns only strip-guards and
tests. Meanwhile every user-scoped route takes `userId` from the query string or the JSON body
and serves whatever it names.

`docs/system_design/20_AuthN_AuthZ.md` sharp edge 1 frames this as a control-plane problem —
an API-key leak lets a caller overwrite embeddings and swap the serving model. The data-plane
half is unstated: the same leak reads every user's profile and recommendation history.

**The gap is latent, not live.** `GATEWAY_COGNITO_ISSUER` is `""` in `k8s/base/configmap.yaml`
and is set in neither region overlay, so no JWT path is active. Every real caller authenticates
with an API key, and `GatewayPrincipal.ofApiKey` produces a blank subject — meaning
`x-authenticated-subject` is never emitted in production at all. With only trusted-backend
callers, read-any-user is the documented trust model rather than a vulnerability. It becomes one
the day an end-user token reaches the gateway.

This design therefore builds enforcement that is a **no-op under today's configuration** and
correct the moment Cognito is enabled.

## Scope

In scope: every gateway-reachable backend route that accepts a `userId`, reads or writes.

Out of scope:

- `GET /api/online/shards/device`, which is keyed on `deviceId`. No device-to-owner mapping
  exists anywhere in the system, so there is nothing to compare against; inventing one is a
  separate design.
- Machine-to-machine JWTs. Nothing issues them today. If they are ever needed, a
  service-client-id allow-list is the extension point, and §"Principal tiering" says where.
- The control-plane half of sharp edge 1 (`/setembedding`, model activate/rollback). Those are
  not user-scoped; a privilege tier for them is separate work.

## Principal tiering

`GatewayPrincipal` gains a `Tier` (`SERVICE` | `USER`) and an `appUserId`.

| Constructor | Tier | `appUserId` | Rationale |
|---|---|---|---|
| `ofApiKey` | `SERVICE` | `""` | Today's every caller. Unrestricted, exactly as now. |
| `anonymous()` | `SERVICE` | `""` | Anonymous exists only under `GATEWAY_ALLOW_ANONYMOUS=true`, which is dev/local. Tiering it `USER` would 403 every local request. |
| `ofJwt` | `USER` | claim value | A JWT caller is an end user until something says otherwise. |

The classification is **credential type, not claim presence**. A JWT whose userId claim is
missing is still `USER` tier and is denied on user-scoped routes, rather than falling through to
service-tier freedom. The alternative — "user-tier iff a userId claim is present" — fails open: a
misconfigured claim name would silently promote every end user to unrestricted access, and
nothing would surface it.

A future M2M allow-list belongs here, as a `SERVICE` classification for named `client_id`s
inside `ofJwt`. It is not built now.

## Identity mapping

A Cognito `sub` is a UUID; this system's `userId` is `"1"`, `"42"`. Comparing them directly
would require an identity provider whose `sub` *is* the application userId, which no real user
pool provides and which no configuration could later correct.

`GATEWAY_COGNITO_USER_ID_CLAIM` (default `sub`) names the claim carrying the application userId.
`CognitoJwtVerifier.VerifiedClaims` gains an `appUserId` component read from it. The claim is
read as a JSON scalar — textual or numeric, coerced to string. Absent, blank, object, and array
all yield `""`, which the tiering rule above turns into a denial rather than an exemption.

Defaulting to `sub` means a deployment that genuinely mints application userIds as `sub` needs no
configuration, and the field is not dead weight for it.

**No new forwarded header.** The finding being closed is "the gateway injects an identity header
no backend reads"; adding a second unread `x-authenticated-*` header would reproduce it. The
comparison happens at the gateway, and the gateway is the only component that needs the value.

## The declaration table

`UserScopedRoutes` maps `(serviceName, backendPath)` to a `UserIdSource`: `QUERY("userId")`,
`BODY("userId")`, or — for the one route whose id is not at the top level — a batch form that
reads `instances[].userId` and yields an id only when the array is non-empty and every element
names the same user.

The key is the **backend** service and path, not the gateway path. Three route prefixes —
`/api/users`, `/api/movies`, `/api/catalog` — all resolve to 6010, and `MicroserviceRoute.rewrite`
forwards the suffix verbatim, so `/api/catalog/getuser`, `/api/users/getuser`, and
`/api/movies/getuser` are one handler reached three ways. Keying on gateway paths would mean
enumerating that cross-product and silently missing entries as prefixes are added. Keying on the
backend handler describes it once and covers every prefix that reaches it. `forward()` already
receives both halves of the key as `route` and `targetPath`.

| Service | Backend path | Source |
|---|---|---|
| `recsys-catalog-serving` (6010) | `/getuser`, `/user` | query |
| | `/getrecommendation`, `/recommendation` | query |
| | `/setuserembedding` (write) | query |
| | `/v2/recommend` | body |
| | `/v1/models/recmodel:predict` | body, `instances[].userId` |
| `recsys-online-serving` (7010) | `/online/recommendation`, `/online/features` | query |
| | `/v2/recommend` | body |
| `recsys-model-serving` (8080) | `/api/v1/recommend` | body |
| | `/v2/recommend`, `/v2/sequential/recommend` | body |

Matching is exact, never prefix. Prefix-with-boundary matching is precisely what created the
`/api/catalog` trap that `20_AuthN_AuthZ` §3 documents and that `PROTECTED_PREFIXES` exists to
survive.

The table is also the *source* of that guard. `GatewayAuthenticator.PROTECTED_PREFIXES` is derived
from it — `route.prefix() + backendPath` over `MicroserviceRoute.defaults()` — rather than
restated. Otherwise listing a user-scoped route in `GATEWAY_PUBLIC_PATHS` would make its callers
anonymous, hence SERVICE tier, hence exempt from the very check declared here, with no warning
fired. Two lists that must agree and are edited independently do not stay in agreement.

`/v1/models/recmodel:predict` is worth its own note, because this design's first draft classified
it as item-scoped and wrong. It looks like pairwise item scoring, and its path says nothing about
users — but `PredictInstance` carries a caller-supplied `userId`, and `PairPredictionService`
loads `u2vEmb:<userId>` and returns scores derived from it, plus a user-existence oracle in its
error text. It is the same disclosure class as `/getrecommendation`. The conformance test below is
what caught it, before any of this shipped. Two lessons hold generally: a route's *name* is not
evidence about what it reads, and the id is not always a top-level field — which is why the source
kinds are a small enumeration rather than a single field lookup. Routes with a null `serviceName` — the two optional LLM routes — have no entries and are
never checked.

## Enforcement

In `GatewayRequestForwarder.forward`:

```
if principal.tier() == USER:
    source = UserScopedRoutes.lookup(route.serviceName(), pathWithoutQuery(targetPath))
    if source is present:
        requested = source.extract(targetPath, request.content())
        if principal.appUserId().isBlank()          -> 403
        if requested is absent or blank             -> 403
        if !requested.equals(principal.appUserId()) -> 403
```

Two placement details the call site dictates:

`targetPath` arrives as `rawPath + "?" + rawQuery` (`GatewayProxyService` builds it that way, and
`RecommendationGatewayService` appends `ctx.query()` the same way), so the table is matched
against the segment before the first `?`, and a `QUERY` source parses its parameter out of the
segment after it — not out of the request headers, whose path is still the pre-rewrite gateway
path.

The check runs **after the rate-limit gate and before the circuit-breaker permit is acquired**.
After rate limiting, so a probing caller still spends tokens from their own bucket on every
denial. Before the permit, because `forward` records success or failure on the permit only on
the upstream-response path — returning 403 with a permit in hand would leak it.

`forward` is the choke point for proxied routes: they reach it through `GatewayProxyService`, and
the canonical `POST /api/recommend` reaches it through `RecommendationGatewayService`; both have
already aggregated the request, so body inspection costs no extra buffering. It is also where the
trust boundary is enforced in the other direction — stripping client-supplied identity headers and
injecting the derived ones. Putting the check beside them keeps the proxied path's identity story
in one function, rather than two call sites today and N whenever a handler is added.

It is not the gateway's *only* forwarding path. `LlmProxyService` forwards to the LLM upstream
itself and duplicates the stripping and injection; it does not run this check. That is sound only
while no LLM route is user-scoped — LLM routes carry a null `serviceName`, and the upstream is a
third-party inference endpoint with no user-keyed resources — but "sound because of a null field"
is the same latent exemption as an unnamed backend route, so the premise is enforced instead of
documented: `LlmProxyService`'s constructor rejects a route whose `serviceName` declares any
user-scoped route. A future user-scoped LLM route fails at startup, pointing at this section.

**Path canonicalization is part of the check, not adjacent to it.** Armeria preserves a `.`
segment and rejects only `..`; Tomcat, on 8080, collapses `.`. The two parses diverge, so
`/api/model/api/v1/./recommend` misses the exact-matching table while still reaching the
`/api/v1/recommend` handler — a bypass of every 8080 user-scoped route. `GatewayProxyService.serve`
rejects a `.` or `..` segment with `400` right after `ApiVersion.parse`, before routing. Rejecting
at the edge rather than canonicalizing at the lookup is deliberate: route matching,
`GATEWAY_PUBLIC_PATHS`, rate-limit keying, and the CloudFront cache key all read the same string,
and a lookup-local fix would leave four consumers on a spelling no legitimate client sends. The
rejection applies to service-tier callers too — a malformed-request rejection, not an
authorization decision. `RecommendationGatewayService` is structurally immune: exact-path
registration plus a constant `/v2/recommend` target.

Three deliberate choices:

**Authorize before validate.** A user-tier request on a declared route whose `userId` cannot be
determined — missing parameter, unparseable body — is denied, not forwarded for the backend to
reject. A request whose subject we cannot identify is a request we cannot authorize. Service-tier
callers never reach this branch, so no existing 400 changes shape.

**Exact string equality, no normalization.** The body `userId` is a string on 8080 and an integer
on 6010/7010; it is read as a scalar and compared textually, so a body `42` matches the claim
`"42"` while `"042"` does not. This is the same canonical-spelling discipline `cacheKeyIntParam`
already applies on the CDN-cached routes.

**A fixed 403 envelope.** `GatewayProxyService.gatewayError` with
`"forbidden: request is not scoped to the authenticated user"` — the requested id is never
echoed, and mismatch and missing-claim are indistinguishable in the response.
`gateway_user_scope_rejected_total` mirrors the existing `gateway_origin_secret_rejected_total`,
and a single WARN carries the route name and the principal's non-reversible rate-limit key. No
user ids reach logs.

## Testing

Unit coverage for the tiering rules, claim extraction (including the non-scalar and absent
cases), and table lookup. Forwarder tests for five paths:

1. Service-tier caller on a declared route — forwarded unchanged. This is the regression that
   proves the "no behavior change today" claim.
2. User-tier caller whose claim matches — forwarded.
3. User-tier caller whose claim does not match — 403.
4. User-tier caller with a blank claim — 403.
5. Any caller on an undeclared route — forwarded.

**The conformance test** is what closes the class rather than the instances, and it works by
inverted classification. It scans the three servers' route registrations — `.service(...)`
literals and route constants on 6010 and 7010, `@RequestMapping`/`@PostMapping` on 8080 — and
requires every reachable `(service, backendPath)` to be classified: declared in
`UserScopedRoutes`, or present in an explicit `NOT_USER_SCOPED` set carrying a one-line reason.
A new backend route fails the build until someone classifies it. Prefix registrations —
7010's `Route.builder().pathPrefix("/shards/")` — are classified as a unit under their prefix.

A scanner that looks in the wrong places is a guarantee that quietly does not hold, so the scan
walks the controller tree recursively and is itself policed: a repo-wide sweep asserts that every
`@RestController` lives under `api/rest` and every backend route registration lives in one of the
two scanned mains, failing with the offending path otherwise. Per-service minimum route counts
catch a regex that stops matching outright — without them a silently broken scanner would make the
whole test vacuous while still reporting green. The floors are set to the actual counts, not
below them: a floor with slack under it is a floor that a scanner can lose routes through.

The scanners read the *backend* mains, so they say nothing about the gateway's own route table —
where the same exemption exists from the other end. `MicroserviceRoute`'s 5-arg convenience
constructor defaults `serviceName` to null, and `UserScopedRoutes.lookup` returns null for a null
service, so a route added that way pointing at 6010/7010/8080 would be permanently unchecked and
invisible to the scan. A third test asserts every default route reaching a backend carries a
`serviceName`, and a fourth configures every derived user-scoped path as public and asserts the
never-public guard still demands a credential.

Inferring user-scopedness from source — looking for `requiredIntParam(ctx, "userId")` and
friends — was considered and rejected as brittle. Forbidding omission is a property a scanner can
actually hold. The technique follows `NetworkPolicyEgressManifestTest` and
`DocumentationIndexTest`, which already derive assertions from repository sources.

All of the above must be added to the `-Presilience` profile; tests outside it do not gate PRs.

## What this does not verify

With no Cognito issuer configured in any environment, the real JWT path cannot be exercised
end to end. Tests construct `VerifiedClaims` directly, so they cover the comparison, the tiering,
and the table — not the extraction of a claim from a token a real user pool actually signed. The
first deployment that sets `GATEWAY_COGNITO_ISSUER` is the first real test of
`GATEWAY_COGNITO_USER_ID_CLAIM`, and the failure mode is a 403 on every user-scoped route rather
than an opening.

This is the same shape as the Splunk HEC work, which shipped unverified for environmental
reasons. It is stated here so the next person does not read green tests as end-to-end assurance.

## Documentation

- `docs/system_design/20_AuthN_AuthZ.md`: a new appended section covering user-scope
  authorization, with no `##` renumbering. Sharp edge 1 is rewritten — the control-plane half
  stands; the data-plane half is closed for user-tier callers and open for service-tier callers
  by design.
- `CLAUDE.md`: `GATEWAY_COGNITO_USER_ID_CLAIM` added to the gateway env-var list.
