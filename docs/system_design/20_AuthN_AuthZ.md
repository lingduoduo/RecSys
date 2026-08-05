# AuthN / AuthZ in Recsys-Backend-Service

An investigation of who proves their identity, where that proof is checked, and what
the proof entitles them to. The short answer: **authentication happens once, at the
gateway**, and everything behind it trusts the gateway. Authorization is deliberately
coarse — two narrow privilege tiers sit above "authenticated caller": one guards
operator surfaces on online serving (§5), the other scopes a JWT caller to their own
`userId` (§10).

## The big picture

Seven distinct credentials exist. Only the first two authenticate an external caller;
the rest are narrower gates, receipts, or — in the last row — how this system proves
its own identity to its data tier.

| Credential | Header | Checked by | Failure | Default |
|---|---|---|---|---|
| Gateway API key | `X-API-Key` / `Authorization: Bearer` | `GatewayAuthenticator` | `401` | unset → fail closed |
| Cognito JWT (RS256) | `Authorization: Bearer` | `CognitoJwtVerifier` | `401` / `403` | unset |
| CloudFront origin secret | `x-origin-secret` | `GatewayOriginSecret` | `403` | unset → disabled |
| Operator token | `X-Admin-Token` | `AdminTokenGuard` | `403` | unset → fail closed |
| Submit token (one-time CSRF) | `X-Submit-Token` | `SubmitTokenService` | `403` | disabled |
| Login session token | `Authorization: Bearer` | `LoginInterceptor` | envelope error | disabled |
| Redis password (`REDIS_PASSWORD`) | — (AUTH on connect) | Redis `requirepass` | startup refusal | unset → fail closed |

Three structural facts shape everything below:

- **The gateway is the only front door.** `k8s/base/network-policy.yaml` restricts
  ingress on 6010/7010/8080 to the `recsys-api-gateway` pod selector; catalog and
  model serving additionally admit Prometheus from the `monitoring` namespace for
  their `/metrics` scrape, and online serving admits nothing else. The backends'
  lack of their own authentication is a deliberate consequence, not an oversight.
- **There is no authorization *model*.** No roles, no scopes, no per-resource
  ownership checks. A caller is authenticated or not; the two exceptions are the
  operator token in §5 and the user-scope check in §10.
- **No security framework is used.** There is no `spring-boot-starter-security`, no
  `jjwt`, no `nimbus-jose-jwt` in [`pom.xml`](../../pom.xml). JWT verification is
  hand-rolled on the JDK plus Jackson (§1), which is a deliberate dependency
  trade-off documented in the
  [Cognito JWT design](../superpowers/specs/2026-07-02-gateway-cognito-jwt-auth-design.md).

## 1. Edge authentication

[`GatewayAuthenticator.check`](../../src/main/java/com/recsys/application/gateway/GatewayAuthenticator.java)
accepts **either** credential and tries them in a fixed order: the value of
`X-API-Key` (falling back to the bearer token) is compared against every configured
`GATEWAY_API_KEYS` entry, and only if that fails is the bearer token verified as a
JWT. Matching neither yields `401` with `WWW-Authenticate: Bearer` and
`{"error":"missing or invalid gateway API key"}`.

The API-key comparison uses `MessageDigest.isEqual` and — importantly —
**accumulates with `|=` instead of breaking on the first match**, so the number of
comparisons does not depend on which key matched. `GatewayOriginSecret` repeats the
pattern for the same reason.

`CognitoJwtVerifier` is a dependency-free RS256 verifier:

| Concern | Behavior |
|---|---|
| Algorithm | `RS256` only; any other `alg` → `401`. `kid` is required |
| Claims | `iss` must equal the configured issuer; `exp`/`nbf` honored with **60 s** clock skew; `aud` matched against the array, the scalar, or `client_id` |
| `token_use` | Checked against `GATEWAY_COGNITO_TOKEN_USE` (default `access`) — **only when the claim is present** |
| JWKS cache | 5 min TTL; 2 s fetch timeout |
| Unknown `kid` | Triggers a refetch at most once per 30 s, behind a CAS single-flight that does **not** hold a lock across the I/O — so a flood of tokens with random `kid`s cannot amplify into a JWKS stampede |
| JWKS outage | **Serve-stale**: a transient fetch failure keeps the last-good keys and retries after 30 s, rather than rejecting tokens whose signing key is already cached. `exp` is still validated independently |

### Fail-closed startup

`GatewayAuthenticator.fromEnvironment` **refuses to construct** when there are no API
keys and no Cognito issuer, because such a gateway authenticates nobody and collapses
every caller to `anonymous` — turning any downstream authorization gap into an
unauthenticated one. Running wide open must be explicit:

```
GATEWAY_ALLOW_ANONYMOUS=true   # logs a loud WARN; dev/local only
```

`CognitoConfig.fromEnvironment` fails fast in the same spirit: setting
`GATEWAY_COGNITO_ISSUER` without `GATEWAY_COGNITO_AUDIENCE` throws at startup rather
than silently skipping audience validation.

`k8s/base` sets `GATEWAY_ALLOW_ANONYMOUS: "true"` (the base is dev-shaped); the
`k8s/eks-shared` component flips it to `false` and injects `GATEWAY_API_KEYS` from the
`recsys-gateway-auth` Secret. Operational setup is in
[gateway-auth.md](../runbooks/gateway-auth.md).

## 2. The trust boundary — stripping in, injecting out

Authentication is worth nothing if the credential or a forged identity reaches a
backend. [`buildUpstreamHeaders`](../../src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java)
enforces both directions on every proxied request:

- **Credentials are consumed, never forwarded** — `Authorization`, `x-api-key`, and
  `x-origin-secret` are stripped, along with hop-by-hop headers.
- **Client-supplied `x-authenticated-*` headers are stripped** by a case-insensitive
  16-character prefix match. This is the anti-spoofing half: backends trust those
  headers *only* because the gateway is provably their sole source.
- **The gateway injects the identity it derived** —
  [`GatewayPrincipal.identityHeaders()`](../../src/main/java/com/recsys/application/gateway/GatewayPrincipal.java)
  emits `x-authenticated-subject` / `-client-id` / `-token-use` (non-blank only), plus
  `x-gateway-service`.

The principal never carries the raw credential. An API-key caller becomes
`clientId="service"` with a rate-limit key of `apikey:<first 6 bytes of SHA-256, hex>`
— non-reversible, so logs and rate-limit keys cannot leak the key. A JWT caller keys
on `user:<sub>`, falling back to `client:<client_id>`.

Designs: [credential stripping](../superpowers/specs/2026-07-02-gateway-credential-stripping-design.md),
[principal propagation](../superpowers/specs/2026-07-02-gateway-principal-propagation-design.md).

## 3. Public paths and the never-public guard

`GATEWAY_PUBLIC_PATHS` (default `/health`) lists paths that skip authentication
entirely. Matching is **prefix-with-boundary** — `path.equals(prefix)` or
`path.startsWith(prefix + "/")` — so `/api/usersettings` does not match `/api/users`.

That boundary rule is also the trap: a bare `/api/catalog` entry *would* match
`/api/catalog/user`. Two mechanisms make the mistake survivable:

- **`PROTECTED_PREFIXES`** is consulted **first** in `isPublic`, so those paths are never
  anonymous regardless of configuration. It is two hand-listed prefixes (`/api/catalog/user`,
  `/api/users`) **plus every user-scoped route derived from `UserScopedRoutes`** —
  `route.prefix() + backendPath` for each gateway prefix that reaches a declared handler, so
  `/api/catalog/getrecommendation`, `/api/movies/getuser` and `/api/online/online/features` are
  all covered. Deriving matters because making a user-scoped route public does more than skip
  authentication: an anonymous caller is SERVICE tier, and service tier is exempt from §10, so a
  public entry would silently switch that route's user-scope check off. A second hand-maintained
  list beside `UserScopedRoutes` would drift; this one cannot.
- **`warnOnProtectedOverlap`** logs at startup when a configured public path would
  have exposed a protected prefix — the guard silently saving you is itself a
  misconfiguration worth fixing.

Version segments cannot be used to evade either mechanism. The gateway normalizes
`/api/v1/users` to `/api/users` *before* `authenticator.check` runs (see
[09_API_Gateway §1](09_API_Gateway.md#api-versioning-and-deprecation)), so `PROTECTED_PREFIXES`
and `GATEWAY_PUBLIC_PATHS` are matched against version-free paths and need no versioned
entries. Had versioned prefixes instead been registered in the route table, every entry in
both lists would need a twin, and a missing one would be a silent auth bypass.

The production value lists the two catalog reads as **exact paths**, because
CloudFront drops `Authorization` on exactly those cache behaviors. That coupling is
covered from the caching side in
[12_CDNS §2](12_CDNS.md#2-making-the-two-reads-cacheable--gateway_public_paths).
Design: [never-public user paths](../superpowers/specs/2026-07-19-gateway-never-public-user-paths-design.md).

## 4. Origin authentication — is this request really from our CDN?

The ALB security group is pinned to CloudFront's managed prefix list, but that list
covers *every* AWS account's distributions.
[`GatewayOriginSecret`](../../src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java)
is what proves the request came from *our* distribution: a server-wide decorator that
rejects any non-exempt request lacking a matching `x-origin-secret` with `403`.

Two design details matter operationally:

- **`GATEWAY_ORIGIN_SECRET` is a comma-separated *set*, not a single value** — both
  the old and new secret are accepted during a rotation, so there is no 403 window.
- **`/health` and `/metrics` are exempt**, because the ALB health check, the kubelet
  probes, and the Prometheus scrape reach the pod directly and would otherwise all
  fail, leaving the pod permanently unready.

Rejections increment `gateway_origin_secret_rejected_total` and log **once** — a
scan or a botched rotation would otherwise flood the log with a per-request warning.

## 5. Operator authorization — a privilege tier

Once edge auth is on, the `/api/online` passthrough lets **any authenticated client**
reach online serving's introspection surfaces. Those are operator tools, not client
API, so [`AdminTokenGuard`](../../src/main/java/com/recsys/api/online/AdminTokenGuard.java)
adds a second factor: a constant-time `X-Admin-Token` match against
`SHARD_ADMIN_TOKEN`, wired from the `recsys-online-admin` Secret (`optional: true`).

| Route | Gate |
|---|---|
| `GET /online/ops` | Operator token (Armeria decorator) |
| `GET /shards/shard` | Operator token |
| `POST /shards/topology` (reshard) | Operator token; `403 "reshard disabled"` when unset |
| `GET /shards/device` | Gateway auth only — per-entity read |
| `POST /shards/records` | Gateway auth only — data-plane write |
| `/v2/recommend`, `/online/*` serving | Gateway auth only |

It **fails closed**: `isAuthorized` returns false when the token is unconfigured, so
an unprovisioned deployment returns `403` rather than exposing the surface. The
per-device read is deliberately *not* gated — under the established trust model,
backends legitimately read any device.
Design: [operator surfaces authz](../superpowers/specs/2026-07-19-online-operator-surfaces-authz-design.md).

## 6. Service-local tokens on model serving (8080)

Two Spring-side mechanisms exist that are **not** caller authentication:

**Submit token — one-time CSRF.** `GET /api/v1/token` issues a UUID stored in Redis
with a 300 s TTL; `POST /api/v1/recommend` consumes it via a Lua `GET`-then-`DEL` so
the check-and-consume is atomic and a token cannot be replayed. The issuing response
is `Cache-Control: no-store` — a shared cache handing the same token to two clients
would break single-use semantics. Disabled by default
(`RECSYS_SUBMIT_TOKEN_ENABLED=false`).

**Login session — an end-user session layer that is essentially dormant.**
`POST /api/v1/auth/login` constant-time-matches an API key from `recsys.login.api-keys`
and returns a UUID session token stored at `login:<token>` in Redis for 24 h;
`LoginInterceptor` parses the bearer token on every request and populates the
request-scoped `RequestScopeData`; `NeedLoginAspect` enforces `@NeedLogin`. In
practice this guards **exactly one endpoint** — `POST /api/v1/auth/logout` — and
`recsys.login.api-keys` is absent from `application.yml`, so `isEnabled()` is false
and login returns `401 "login is disabled on this server"` out of the box.

## 7. Signed tokens that are not credentials

[`ConsistencyTokenCodec`](../../src/main/java/com/recsys/application/consistency/ConsistencyTokenCodec.java)
issues an HMAC-SHA256 receipt (`X-Consistency-Token`) proving an online event was
durably accepted — a read-your-writes proof, not an identity. It is worth reading as
the repo's best-shaped token: a **versioned** header (`{"alg":"HS256","typ":"ECT","v":1}`),
**subject-bound** to the `userId`, a fixed 24 h lifetime, a 2048-character ceiling,
and a constructor that rejects a secret under 32 UTF-8 bytes. Semantics live in
[15_Eventual_Consistency](15_Eventual_Consistency.md).

[`RecommendationCursorCodec`](../../src/main/java/com/recsys/application/pagination/RecommendationCursorCodec.java)
is the same shape applied to pagination: an HMAC-SHA256 signature over a versioned
(`VERSION = "3"`), user- and query-bound payload, with constant-time comparison, an
issued-at expiry, a 2048-character ceiling, and an active/previous key pair for
rotation. Unsigned `v2:` tokens are accepted only behind an explicit compatibility
flag and upgrade on the next page. Mechanics are in
[19_Pagination §4](19_Pagination.md#4-implemented-recommendation-optimization);
key rotation is in
[recommendation-cursor-key-rotation.md](../runbooks/recommendation-cursor-key-rotation.md).

Neither token authenticates a caller — both bind data to a subject that the gateway
already authenticated. They are the two places in the repo where a signed wire format
is done properly, and worth copying from.

## 8. The L3/L4 access-control list

Everything above is L7. The control that makes it *sufficient* is
[`network-policy.yaml`](../../k8s/base/network-policy.yaml): the backends authenticate nobody
because the policy is what proves the gateway is their only reachable caller.

Each of the four services declares `policyTypes: [Ingress, Egress]` and permits an explicit
destination set. The relay is the deliberate exception — `policyTypes: [Ingress]`, leaving egress
unrestricted, because its destinations (MySQL, Kafka, and in EKS ElastiCache) are partly external
and partly per-overlay, so an allow-list there would black-hole delivery the moment it drifted.

The egress half is the half that rots. Ingress rules are stated once and stay true; egress rules
encode the *addresses of dependencies*, and those move — a new upstream in the ConfigMap, a flag
that opens a connection, an overlay that relocates Redis outside the cluster. Six such gaps had
accumulated by 2026-08. `NetworkPolicyEgressManifestTest` is the response: it derives every
upstream address from `recsys-config` and requires a matching rule, so the two sets can no longer
diverge without failing a PR. Ownership is declared rather than derived — `recsys-config` is one
ConfigMap `envFrom`'d into all five workloads, so possession of an env var proves nothing about
who dials it.

Design: [NetworkPolicy egress conformance](../superpowers/specs/2026-08-05-networkpolicy-egress-conformance-design.md).

## 9. Testing

- **Edge auth** — `GatewayAuthenticatorTest` (key match, bearer-as-key, public paths,
  never-public guard, fail-closed startup), `CognitoJwtVerifierTest` (alg, kid, iss,
  aud, exp/nbf skew, token_use), `CognitoJwtVerifierJwksTest` (serves from cache
  within TTL, serves stale after a failed refetch, backoff during an outage, unknown
  `kid` with nothing cached).
- **Trust boundary** — `GatewayRequestForwarderTest` (strips spoofed `x-authenticated-*`,
  strips consumed credentials and the origin secret, injects the principal),
  `GatewayPrincipalTest` (JWT and API-key principals, anonymous).
- **Origin secret** — `GatewayOriginSecretTest` (rotation set, exempt paths,
  constant-time), `GatewayOriginSecretMetricsTest` (counter).
- **Operator gate** — `AdminTokenGuardTest` (fail-closed unset, constant-time match,
  decorator `403`).
- **Service-local** — `SubmitTokenServiceTest` (single-use consume),
  `SubmitTokenCacheHeaderTest` (`no-store`), `ConsistencyTokenCodecTest`.
- **Data tier** — `LettuceClientFactoryTest` (TLS on/off, ACL username vs default-user
  AUTH, replica URIs inheriting both, the Spring-properties path, and the three guard
  cases: blank password refused, opt-out accepted, password needs no opt-out),
  `RedisAuthManifestTest` (every Redis client wired from the Secret, the relay
  deliberately not, `requirepass`/`masterauth`/`auth-pass` on the servers, no probe
  passing `-a`, and no manifest in any overlay setting `REDIS_ALLOW_NO_AUTH`).

## Data-tier authentication

Until 2026-08 every service connected to Redis as the unauthenticated `default` user over a
plaintext socket. `LettuceClientFactory` read `REDIS_PASSWORD` and no manifest set it — a
supported control that nothing switched on.

Redis now requires a password: `--requirepass` on the primary and replica, `--masterauth` so the
replica can authenticate to the primary, and `sentinel auth-pass` in the Sentinel template,
substituted from the environment by the init container because a ConfigMap cannot hold a secret.
Clients read `REDIS_PASSWORD` from the `redis-password` key of `recsys-secrets`.

`LettuceClientFactory` refuses to open a connection without a credential unless
`REDIS_ALLOW_NO_AUTH=true` — the same fail-closed shape as `GatewayAuthenticator.fromEnvironment`
in §1. Local development and the test suite set it; no overlay does.

The client also supports `REDIS_USERNAME` (Redis 6 ACL login) and `REDIS_TLS`. Both are unused
today: the in-cluster Redis has no certificates, and per-service ACL users are a separate project.
They exist so that project is configuration rather than a client change.

Three things this does not do. Traffic between the pods and Redis is still unencrypted, so the
NetworkPolicy remains the only control on who can read it in transit. All five clients share
one credential over the whole keyspace, despite cleanly disjoint key ownership — that is the ACL
work, not this. And Sentinel's own port 26379 has no `requirepass` of its own: `sentinel auth-pass`
is how a sentinel authenticates *to* the primary, not how it authenticates callers. Anyone with
network reach to 26379 can still issue `SENTINEL FAILOVER mymaster` and move the write leader —
exactly the "NetworkPolicy is the only control" gap this closes for 6379, left open on the port
that decides which node 6379 *is*.

Design: [Redis transport authentication](../superpowers/specs/2026-08-05-redis-transport-auth-design.md).

## 10. User-scope authorization — is this caller allowed to name this user?

§1–§4 answer "is this caller authenticated". This section answers "may this caller act on *this*
user", which is a separate question and was, until now, unasked: `userId` is an ordinary request
field, so any authenticated caller could name any user.

The rule is one comparison, applied at the gateway:

- **Credential type decides the tier.** `GatewayPrincipal.Tier` is `USER` for a JWT caller and
  `SERVICE` for an API key or (dev-only) anonymous. Service-tier callers are exempt — the trust
  model is that they are backends legitimately acting for many users, which is what
  `ModelRateLimiter` already assumes when it keys on the served userId rather than the caller.
- **User-tier callers may act only on their own id.** `GATEWAY_COGNITO_USER_ID_CLAIM` (default
  `sub`) names the JWT claim carrying the application userId; the gateway compares it, as an exact
  string, to the `userId` in the request.
- **Anything indeterminate is a denial.** A blank claim, a missing `userId`, an unparseable body
  — all 403. Tiering by credential type rather than by claim presence is what makes this hold: a
  JWT whose claim did not resolve stays user-tier and is denied, instead of falling through to
  service-tier freedom.

`UserScopedRoutes` declares which backend routes take a `userId` and how it arrives, keyed on
`(backend service, backend path)` — not on the gateway path, because `/api/users`, `/api/movies`,
and `/api/catalog` all resolve to 6010 and `MicroserviceRoute.rewrite` forwards the suffix
verbatim, so one handler is reachable under three prefixes. Three source kinds cover every route:
`QUERY` (a query-string parameter), `BODY` (a top-level `userId` field), and `BODY_INSTANCES`
(every element of a TF-Serving-shaped `instances[]` array must name the same user). The third kind
exists because `POST /v1/models/recmodel:predict` turned out to be user-scoped: `PredictInstance`
carries a caller-supplied `userId`, and `PairPredictionService` loads `u2vEmb:<userId>` and returns
scores derived from it — a fact the route had first been excused past, and only the conformance
test below caught. `UserScopedRouteCoverageTest` requires every gateway-reachable backend route to
be declared in `UserScopedRoutes` or explicitly listed as not user-scoped, so the enforcement
cannot silently develop holes as routes are added; a second test in the same class sweeps the
whole `src/main/java` tree for route registrations and Spring controllers outside the locations
the first test scans, so the coverage claim cannot be undermined by a route registered somewhere
the scanner never looks.

Enforcement lives in `GatewayRequestForwarder.forward`, beside the credential stripping and
identity injection of §2. It runs after rate limiting (so a probing caller spends their own
tokens) and before the circuit-breaker permit is acquired (so a denial cannot leak one). Denials
return `403` with a fixed body (`forbidden: request is not scoped to the authenticated user`),
increment `gateway_user_scope_rejected_total`, and log once.

`forward` is the choke point for every *proxied* route — `GatewayProxyService` and
`RecommendationGatewayService` both reach it — but it is **not** the gateway's only forwarding
path. `LlmProxyService` forwards to the LLM upstream itself, duplicating §2's stripping and
injection, and does not run the user-scope check. That is sound only because no LLM route can be
user-scoped, and that premise is enforced rather than asserted: `LlmProxyService`'s constructor
refuses a route that reaches a backend declaring any user-scoped route, naming this section in the
failure. Adding a user-scoped LLM route therefore fails at startup instead of forwarding
unchecked. Two forwarding paths is the real shape here; treating it as one was the mistake.

The guard resolves the route's **target**, not its label, and so does `forward`'s own lookup —
both go through `UserScopedRoutes.effectiveServiceName`, which falls back to the `serviceName` of
whichever known route shares the same authority. A guard on the declared name alone could never
have fired: `MicroserviceRoute.fromEnvOptional`, the only thing that builds an LLM route in
production, always passes `serviceName = null`, and the 5-arg constructor defaults it to null too.
So the realistic misconfiguration — `LLM_SERVICE_URL` pointed at 8080 — produced a route both the
guard and the lookup waved through, and `/api/llm/api/v1/recommend` forwarded with no check at all.
A route cannot opt out of user-scope enforcement by declining to name itself.

**The path the check sees must be the path the backend sees.** Armeria preserves a `.` segment
(it rejects only `..`); Tomcat, which serves 8080, collapses it. So
`/api/model/api/v1/./recommend` missed the `UserScopedRoutes` table — matching there is exact, by
design — while still reaching the Spring handler registered for `/api/v1/recommend`, which made
every 8080 user-scoped route bypassable by respelling the path. `GatewayProxyService.serve`
rejects any path carrying a `.` or `..` segment with `400`, immediately after `ApiVersion.parse`
and before routing. Rejecting at the edge rather than canonicalizing at the lookup is the point:
route matching, `GATEWAY_PUBLIC_PATHS`, rate-limit keying, and the CloudFront cache key all key on
the same string, and a fix inside the lookup would have left the other four on whatever spelling
the client chose. This is the one control in §10 that also applies to service-tier callers — it is
a malformed-request rejection, not an authorization decision, and no published route contains a
dot segment. `RecommendationGatewayService` needs no equivalent guard: it is registered at two
exact paths and builds a constant `targetPath` of `/v2/recommend`, so no client-supplied path
segment reaches an upstream through it.

A percent-encoded separator is the same disagreement reached by a different spelling, and is
rejected alongside it. Armeria decodes unreserved characters but deliberately leaves `%2F`
encoded, so `/api/model/api/v1/.%2Frecommend` is *one* segment to every control here and two to
any backend that decodes it. Tomcat rejects an encoded solidus by default, which made this inert —
but that is a setting (`encodedSolidusHandling`), not a guarantee, and no route the gateway
publishes takes a path segment containing a literal `/`. Depending on a downstream default to hold
a security boundary is how the `.` case got missed in the first place.

**What this does not yet prove.** No environment sets `GATEWAY_COGNITO_ISSUER`, so every caller
today is service-tier and this section describes a path that is never taken in production. Tests
construct verified claims directly; the extraction of a real claim from a real user pool is
untested until the first deployment that enables Cognito. Its failure mode is a 403 on every
user-scoped route, not an opening.

Design: [user-scope authorization](../superpowers/specs/2026-08-05-gateway-user-scope-authorization-design.md).

## Sharp edges — notes

1. **Authorization is two narrow checks, not a model.** §5's operator token predates this work
   and still gates only online serving's introspection surfaces — `GET /online/ops`,
   `GET /shards/shard`, and `POST /shards/topology` — behind its own independent fail-closed
   default. §10 adds a second, unrelated check: a JWT (user-tier) caller may act only on its own
   `userId`, and only on the routes `UserScopedRoutes` declares. Outside those two checks — and for
   every service-tier caller, including on a user-scoped route — any authenticated caller can reach
   every other routed data-plane path, including control-plane writes such as
   `/api/catalog/setembedding` (overwrite item embeddings on 6010) and
   `/api/model/api/v1/model/versions/activate` and `/rollback` (swap the serving model). Those sit
   in the same privilege tier as a catalog read. The trust model is "callers are trusted backends",
   so this is consistent — but it means an API-key leak is still a control-plane compromise, and,
   because API keys are service-tier, still a read of every user.
2. **`k8s/base` runs wide open.** `GATEWAY_ALLOW_ANONYMOUS: "true"` lives in the base
   configmap; only the `eks-shared` component flips it to `false`. A new overlay that
   composes `../base` without `../eks-shared` inherits anonymous access silently —
   the fail-closed guard cannot help, because the opt-in is present.
3. **The session layer's `userId` is the API key.** `LoginController` calls
   `loginTokenService.create(request.getApiKey())`, so the Redis value at
   `login:<token>` is the API key in plaintext and `RequestScopeData.getUserId()`
   returns a credential. Anything that logs or keys on that "user id" is handling a
   secret.
4. **`@NeedLogin` denial is a `200`.** `NeedLoginAspect` returns
   `ApiResponseUtil.error("user not logged in")` — an error envelope with a success
   status code, not `401`. It currently guards only logout, so the blast radius is
   nil, but the pattern would mislead any client that checks status codes.
5. **A JWT with no `token_use` claim passes the `token_use` check.** The validation is
   skipped when the claim is blank, so the restriction only tightens tokens that
   declare a use.
6. **Backend safety depends on the NetworkPolicy, not on the backends.** 6010, 7010,
   and 8080 authenticate nobody. Reaching a backend pod directly — a misapplied
   policy, a debug port-forward, a service exposed by a new manifest — bypasses every
   control in §1–§4 at once.
7. **The NetworkPolicy is base-only.** No overlay patched it until the ElastiCache egress patch
   in `k8s/eks-shared`, yet both EKS overlays relocate Redis outside the cluster. An overlay that
   changes *where* a dependency lives changes it out from under the ACL, and nothing in `k8s/base`
   can notice — the conformance test reads base, so overlay coverage stops at asserting the patch
   exists.
8. **Enforcement is CNI-dependent.** EKS's default VPC CNI does not enforce NetworkPolicy unless
   policy support is explicitly enabled. "The NetworkPolicy protects the backends" is therefore a
   claim about cluster configuration, not about anything in this repo. If it is not enforced, §8's
   rules are documentation and the backends — which authenticate nobody — are reachable by any pod
   in the cluster.
9. **Nothing permits egress on 443.** The conformance test covers what the ConfigMap names, and
   the ConfigMap names no HTTPS endpoint. The Cognito JWKS fetch (once `GATEWAY_COGNITO_ISSUER` is
   set), IRSA/STS, Cloud Map, and SQS all leave the cluster on 443 against no rule. On an
   enforcing CNI, turning on Cognito JWT auth would fail every verification — and §8's test would
   still be green, because a destination absent from the ConfigMap is a destination it cannot know
   about.
