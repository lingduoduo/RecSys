# AuthN / AuthZ in Recsys-Backend-Service

An investigation of who proves their identity, where that proof is checked, and what
the proof entitles them to. The short answer: **authentication happens once, at the
gateway**, and everything behind it trusts the gateway. Authorization is deliberately
coarse — two narrow privilege tiers sit above "authenticated caller": one is the
operator token, checked directly by online serving's own introspection surfaces (§5)
and, since §11, also enforced by the gateway itself for every route it classifies
`OPERATOR` across all three backends; the other scopes a JWT caller to their own
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
  operator token (§5, and, at the gateway, §11) and the user-scope check in §10.
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
  `/api/users`) **plus every user-scoped route derived from `BackendRoutePolicy`** —
  `route.prefix() + backendPath` for each gateway prefix that reaches a declared handler, so
  `/api/catalog/getrecommendation`, `/api/movies/getuser` and `/api/online/online/features` are
  all covered. Deriving matters because making a user-scoped route public does more than skip
  authentication: an anonymous caller is SERVICE tier, and service tier is exempt from §10, so a
  public entry would silently switch that route's user-scope check off. A second hand-maintained
  list beside `BackendRoutePolicy` would drift; this one cannot.
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
API, so [`AdminTokenGuard`](../../src/main/java/com/recsys/application/auth/AdminTokenGuard.java)
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
upstream address from the union of `recsys-config` and every base Deployment's inline `env:`, and
requires a matching rule, so the two sets can no longer diverge without failing a PR. The
Deployment half was added after `SPLUNK_HEC_URL` — a literal env value on all four serving
Deployments, not a ConfigMap key — turned out to be dialing an unpermitted `splunk:8088`.
Ownership is declared rather than derived — `recsys-config` is one
ConfigMap `envFrom`'d into all five workloads, so possession of an env var proves nothing about
who dials it. A Deployment env var is the opposite case: it names its own dialer.

Two rules in that file are worth reading directly. Egress to `app: splunk` on 8088 exists because
every serving workload ships structured logs there, and the appender's at-most-once, drop-on-full
delivery means a blocked connection loses events with no error anywhere — the failure is
indistinguishable from a service that logged nothing. The DNS rule is scoped to
`namespaceSelector: kubernetes.io/metadata.name=kube-system` rather than left destination-free: a
NetworkPolicy rule with no `to[]` permits its port to *every* address, so the bare `ports: [53]`
this file used to carry was an unrestricted outbound channel from every workload. It stops at the
namespace rather than adding `podSelector: k8s-app=kube-dns` because CoreDNS's own labels vary
across distributions, so a podSelector pinned to one would break silently on another. Note that
neither selector matches NodeLocal DNSCache, which is host-networked and therefore carries the
node's IP: a cluster running it needs an additional `ipBlock` peer for `169.254.20.10/32` in the
same rule.

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

The client also supports `REDIS_USERNAME` (Redis 6 ACL login) and `REDIS_TLS`. `REDIS_TLS` is
still unused: the in-cluster Redis has no certificates. `REDIS_USERNAME` is no longer inert —
authorization is covered below.

Two things this does not do. Traffic between the pods and Redis is still unencrypted, so the
NetworkPolicy remains the only control on who can read it in transit. And Sentinel's own port
26379 has no `requirepass` of its own: `sentinel auth-pass` is how a sentinel authenticates *to*
the primary, not how it authenticates callers. Anyone with network reach to 26379 can still issue
`SENTINEL FAILOVER mymaster` and move the write leader — exactly the "NetworkPolicy is the only
control" gap this closes for 6379, left open on the port that decides which node 6379 *is*.

Design: [Redis transport authentication](../superpowers/specs/2026-08-05-redis-transport-auth-design.md).

**Authorization is a separate concern from authentication, and was closed later.** The paragraph
above shipped every workload authenticating as `default`, and at the time this doc claimed the
five clients shared one credential "despite cleanly disjoint key ownership." A 2026-08-05 ACL
audit recorded that same claim, and it does not survive contact with the call graph: catalog,
model, and online serving all read the same `i2vEmb:`/`u2vEmb:` embedding keyspace and the same
`topk:` trending keyspace, so key ownership was never disjoint on the read side. Splitting reads
apart would require moving those reads behind a service boundary, not an ACL change, and is not
attempted. What the call graph does support is a write split: each key prefix has exactly one
service that writes it (catalog writes `i2vEmb:*`/`u2vEmb:*`; online writes `sr:*`/
`shard:topology`/`rate:online:*`; model writes `submit_token:*`/`login:*`; each service writes
only its own `svc:registry:*` key), even though several services read across those boundaries.

`k8s/base/redis-users.acl.template` now defines six ACL users — `default` plus one per workload
(`catalog`, `model`, `online`, `gateway`, `reconciliation`) — mounted via `--aclfile` on both the
primary and replica StatefulSets and wired to each workload's own `REDIS_USERNAME`. Each
non-default user is scoped to `-@all +@read +@write +@connection -@dangerous` over its own write
prefixes, `+@scripting` where `EVAL` is on the path (catalog, model, online — the trending read,
sharded record write, topology publish, rate limiting, and submit-token consume all run as Lua),
and read-only (`%R~`) grants onto the prefixes it reads but does not own. `topk:*` is the one
prefix granted full read-write (`~`, not `%R~`) to all three serving users regardless of logical
ownership, because `ShardedTopKStore` reads it through `EVAL` and Redis requires full read-write
permission on every key a script touches. `default` keeps `~* &* +@all`, so the original
`redis-password` credential — the one Flink authenticates as to write `u2vEmb:*` and `topk:*` — is
still a full-access credential, including `FLUSHALL`; the least-privilege boundary here is the
five service users, not the instance as a whole. See
[the ACL users section](../runbooks/redis-auth.md#acl-users) for the mechanics, including a
measured, load-bearing gotcha: an ACL file that omits `user default` does not leave `requirepass`
governing it, it silently disables authentication.

This still has two limits, both unchanged by the ACL work. The EKS overlays point every client at
ElastiCache instead of this Redis, and ElastiCache authorizes access through RBAC user groups
managed via the AWS API — a different mechanism that none of this ACL file touches. And nothing
here is enforced anywhere today: no EKS cluster exists in either region, so this is manifest
correctness plus a merge-blocking conformance test (`RedisAclManifestTest`), not a running
guarantee.

Design: [Redis per-service ACL users](../superpowers/specs/2026-08-09-redis-per-service-acl-design.md).

MySQL is the other data tier, and closes a gap Connector/J leaves open by default rather than one
this codebase left unset. `MySqlConnectionSettings`'s compact constructor refuses to build when
`MYSQL_ENABLED=true` and `MYSQL_URL` does not set `sslMode=VERIFY_IDENTITY`, and refuses a URL that
still carries the deprecated `useSSL` property (Connector/J 8 lets `sslMode` override it silently,
so the two together read as one thing and behave as another). MySQL client construction is
otherwise unconditional: `CatalogComponent.fromEnvironment()`, which `RecSysServer` (6010) calls on
every startup, builds `MySqlConnectionSettings` regardless of whether MySQL is enabled, so the
guard is live the moment `MYSQL_ENABLED=true` is set — a URL without a verified `sslMode` fails 6010
at pod start. `OnlinePredictionServer` (7010) builds it only when durable online event acceptance
is on (`ONLINE_DURABLE_EVENTS_ENABLED=true`), which itself requires `MYSQL_ENABLED=true` and throws
if it is unset — so the same guard applies to 7010 whenever that feature is enabled, not merely
whenever MySQL is. A third construction site is ungated in the same way 6010 is:
`ReconciliationCommand.main` builds `MySqlConnectionSettings` unconditionally, unlike
`OutboxRelayCommand.main`, which returns early when `ONLINE_DURABLE_EVENTS_ENABLED` is false. Its
CronJob `envFrom`s `recsys-config`, so a `MYSQL_URL` without a verified `sslMode` fails the
reconciliation job at every scheduled run — as a `CrashLoopBackOff` on a periodic pod rather than
at a rollout, which is the quieter of the two failures to notice.

`VERIFY_IDENTITY` rather than `REQUIRED`: `REQUIRED` encrypts the connection but verifies no
certificate, so it stops silent plaintext but not an active man-in-the-middle presenting any
certificate at all. Against RDS this costs no extra provisioning: per `MySqlConnectionSettings`'s
javadoc, Amazon's CAs are already in the JVM truststore, so an RDS certificate verifies with no
extra truststore work. `k8s/eks/redis-elasticache-patch.yaml` documents the same shape for the
other data tier's TLS — that ElastiCache's certificate verifies against the JVM truststore because
it already trusts the Amazon Root CA that ElastiCache's certificates chain to — though that file
only speaks to ElastiCache, not RDS.

Loopback hosts (`localhost`, `127.0.0.1`, `[::1]`) are exempt from the whole requirement, including
the `useSSL` rejection. This is a host test on the URL, not an opt-out flag: the host `k8s/base`
sets is `mysql`, and any RDS endpoint is a DNS name, so no manifest value can satisfy the loopback
test and disable the guard the way a manifest can set `REDIS_ALLOW_NO_AUTH=true` above. That is the
shape the Redis finding lacked — there, the opt-out is a flag a manifest could in principle carry;
here, exemption is unreachable from any deployed configuration.

The consequence is real but bounded: `MYSQL_ENABLED` defaults to `"false"` in every manifest, so
today this guard is dormant everywhere, and nothing in this repository deploys a MySQL server for
it to run against — the requirement is unverified against a real TLS handshake. `k8s/base/configmap.yaml`
now sets `MYSQL_URL` to a value that satisfies the guard (`?sslMode=VERIFY_IDENTITY`), so enabling
MySQL by flipping `MYSQL_ENABLED` alone does not trip it; a manifest change that touches `MYSQL_URL`
without preserving that parameter would.

Design: [MySQL transport TLS](../superpowers/specs/2026-08-05-mysql-transport-tls-design.md).

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

`BackendRoutePolicy` declares which backend routes take a `userId` and how it arrives, keyed on
`(backend service, backend path)` — not on the gateway path, because `/api/users`, `/api/movies`,
and `/api/catalog` all resolve to 6010 and `MicroserviceRoute.rewrite` forwards the suffix
verbatim, so one handler is reachable under three prefixes. (This is one access class among four —
`NO_PROXY`, `OPERATOR`, `USER_SCOPED`, `AUTHENTICATED` — that `BackendRoutePolicy` assigns to every
backend route; §11 covers the table as a whole. This section covers only the `USER_SCOPED` class,
which absorbed the entire remit of the former, single-purpose `UserScopedRoutes` class.) Three
source kinds cover every user-scoped route: `QUERY` (a query-string parameter), `BODY` (a
top-level `userId` field), and `BODY_INSTANCES` (every element of a TF-Serving-shaped
`instances[]` array must name the same user). The third kind exists because
`POST /v1/models/recmodel:predict` turned out to be user-scoped: `PredictInstance` carries a
caller-supplied `userId`, and `PairPredictionService` loads `u2vEmb:<userId>` and returns scores
derived from it — a fact the route had first been excused past, and only the conformance test
below caught. `BackendRouteCoverageTest` requires every gateway-reachable backend route to be
classified in `BackendRoutePolicy` as one of the four access classes — not merely user-scoped or
not — so the enforcement cannot silently develop holes as routes are added; a second test in the
same class sweeps the whole `src/main/java` tree for route registrations and Spring controllers
outside the locations the first test scans, so the coverage claim cannot be undermined by a route
registered somewhere the scanner never looks.

Enforcement lives in `GatewayRequestForwarder.forward`, beside the credential stripping and
identity injection of §2. It runs after rate limiting (so a probing caller spends their own
tokens) and before the circuit-breaker permit is acquired (so a denial cannot leak one). Denials
return `403` with a fixed body (`forbidden: request is not scoped to the authenticated user`),
increment `gateway_user_scope_rejected_total`, and log once.

`forward` is the choke point for every *proxied* route — `GatewayProxyService` and
`RecommendationGatewayService` both reach it, and since §11 it also enforces the `NO_PROXY` and
`OPERATOR` classes there — but it is **not** the gateway's only forwarding path. `LlmProxyService`
forwards to the LLM upstream itself, duplicating §2's stripping and injection, and does not
consult `BackendRoutePolicy` for the request path at all — no user-scope check, no operator-token
check, nothing. That is sound only because no LLM route can reach a backend `BackendRoutePolicy`
classifies, and that premise is enforced rather than asserted: `LlmProxyService`'s constructor
refuses a route that resolves to *any* known backend, naming this section in the failure —
regardless of which access class that backend happens to declare, so a future `NO_PROXY` or
`OPERATOR` backend closes the same gap a `USER_SCOPED` one would. Adding an LLM route that reaches
a classified backend therefore fails at startup instead of forwarding unchecked. Two forwarding
paths is the real shape here; treating it as one was the mistake.

The guard resolves the route's **target**, not its label, and so does `forward`'s own lookup —
both go through `BackendRoutePolicy.effectiveServiceName`, which falls back to the `serviceName`
of whichever known route shares the same authority. A guard on the declared name alone could never
have fired: `MicroserviceRoute.fromEnvOptional`, the only thing that builds an LLM route in
production, always passes `serviceName = null`, and the 5-arg constructor defaults it to null too.
So the realistic misconfiguration — `LLM_SERVICE_URL` pointed at 8080 — produced a route both the
guard and the lookup waved through, and `/api/llm/api/v1/recommend` forwarded with no check at all.
A route cannot opt out of this enforcement by declining to name itself.

**The path the check sees must be the path the backend sees.** Armeria preserves a `.` segment
(it rejects only `..`); Tomcat, which serves 8080, collapses it. So
`/api/model/api/v1/./recommend` missed the `BackendRoutePolicy` table — matching there is exact, by
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

1. **The operator gate on control-plane writes is a gateway property, not a system property.**
   §11's `BackendRoutePolicy` now requires `X-Admin-Token` at the gateway for every `OPERATOR`
   route across all three backends — `/api/catalog/setembedding` (overwrite item embeddings on
   6010), `/api/model/api/v1/model/versions/activate`/`/rollback`/`/preload` (swap the serving
   model on 8080), and `/api/online/online/ops`. §10 remains the other check: a JWT (user-tier)
   caller may act only on its own `userId`, on the routes `BackendRoutePolicy` marks `USER_SCOPED`.
   Neither check does anything for a caller that reaches a backend directly instead of through the
   gateway — sharp edge 6 is unchanged: 6010, 7010, and 8080 authenticate nobody. For
   `/api/catalog/setembedding` and the model version endpoints, the gateway's `X-Admin-Token` check
   is the *only* control-plane authorization that exists anywhere in the system; a direct pod
   connection reaches them with no credential at all, operator or otherwise.
   `/shards/topology`, `GET /shards/shard`, and `GET /online/ops` are the exception — 7010 already
   guards those three with its own `AdminTokenGuard` (§5), so the gateway's check there is
   redundant defense-in-depth, not the only line. So: control-plane writes require the operator
   token when reached **through the gateway**. Whether they are also reachable without one depends
   on which backend they live on, and for two of the three routes above, they are.
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
   set), IRSA/STS, Cloud Map, SQS, and PostHog feature flags (`PostHogFeatureFlagProvider`, which
   POSTs `distinct_id` and `person_properties` to an external SaaS) all leave the cluster on 443
   against no rule. On an enforcing CNI, turning on Cognito JWT auth would fail every verification
   — and §8's test would still be green, because a destination named in neither `recsys-config`
   nor a base Deployment's inline `env:` is a destination it cannot know about. Overlay-patched
   addresses are outside its reach for the same reason.

## 11. The gateway proxy policy — what the gateway is willing to forward

[`BackendRoutePolicy`](../../src/main/java/com/recsys/application/gateway/BackendRoutePolicy.java)
classifies every backend route the gateway can reach into one of four access classes:

| Class | Meaning |
|---|---|
| `NO_PROXY` | Never proxied — telemetry and diagnostics, reachable only on the pod |
| `OPERATOR` | Requires `X-Admin-Token`, for every caller including service-tier ones |
| `USER_SCOPED` | Requires a user-tier caller to name its own `userId` — §10 |
| `AUTHENTICATED` | Proxied to any authenticated caller — ordinary data paths |

Classification is an allow-list: an exact-path match is tried first, then a three-entry prefix
table (`/actuator` → `NO_PROXY`, `/shards` → `AUTHENTICATED`, `/api/v1/knowledge-bases` →
`AUTHENTICATED`). A path matching neither is denied exactly like one explicitly marked
`NO_PROXY`:
[`GatewayRequestForwarder.enforceRoutePolicy`](../../src/main/java/com/recsys/application/gateway/GatewayRequestForwarder.java)
returns `404 {"error":"no route found"}` for both, byte-identical to a path that was never routed
at all — a caller cannot distinguish "this route exists but is withheld" from "this route was
never registered." `BackendRouteCoverageTest` enforces the allow-list property at build time: it
scans all three backend mains, and fails if any route it finds has no entry in
`BackendRoutePolicy`, so an unclassified route added tomorrow is unreachable through the gateway
rather than silently exposed by it.

**Prefix entries exist for paths that cannot be written as exact strings** — three of them, for
three different reasons. `/actuator`'s membership is configuration, not source (below).
`/shards` is a single Armeria `pathPrefix` whose sub-paths are dispatched inside the handler.
`/api/v1/knowledge-bases` is there because two of `KnowledgeBaseController`'s four handlers are
declared with a Spring path *template* — `/knowledge-bases/{knowledgeBaseId}` — and a template is
not a path: declared as an exact entry it matches only the literal brace-bearing string that the
coverage scanner emits and no client ever sends, so every concrete id would 404 while both
coverage tests stayed green. A prefix covers the collection path and every id in one entry. The
corollary is that a prefix's own path must not *also* be declared exactly — exact wins the lookup,
which would make the prefix's own branch dead code — and
`BackendRouteCoverageTest#noPrefixEntryShadowsADeclaredExactPath` fails the build on it.

**Telemetry is no longer proxied, and nothing legitimate depended on it going through the
gateway.** `/metrics` (Armeria, 6010 and 7010), `/actuator/*` (Spring, 8080), 8080's diagnostic
`/health/*` surfaces (`/health/jvm`, `/health/gc`, `/health/metrics`, `/health/cache`,
`/health/ab-tests`, `/health/load`, `/health/live`, `/health/ready`), and each backend's plain
liveness/readiness paths (6010's `/health`, `/health/ready`, `/health/load`; 7010's `/health`,
`/health/live`, `/health/ready`) are all `NO_PROXY` — the complete `NO_PROXY` set, not a sample.
The gateway's own `/metrics` on 8010 is a **different surface** and is not in this table at all:
it is served directly by `MicroserviceGatewayServer` (`sb.service("/metrics", …)`), never routed
through `GatewayRequestForwarder`, so no access class applies to it and it stays reachable — by
design, since §4 deliberately exempts it from `GATEWAY_ORIGIN_SECRET` so the Prometheus scrape
works. Nothing in this table withholds it, and a claim that it is `NO_PROXY` would be asserting a
control that does not exist. The `ServiceMonitor`s in
[`k8s/base/servicemonitor.yaml`](../../k8s/base/servicemonitor.yaml) scrape each backend's own
Service directly, not through the gateway; the k8s startup/liveness/readiness probes hit the pod
directly; and `UpstreamEndpointGroups`' upstream health checking dials `baseUri + healthPath` on
the backend directly rather than through the proxy path. `/actuator`'s membership in `NO_PROXY` is
declared, not scanned — Spring's endpoint exposure is a configuration property
(`management.endpoints.web.exposure.include`, `application.yml`), invisible from a source scan, so
`BackendRouteCoverageTest` cannot cross-check that declaration the way it does the enumerable
routes. Whether `/actuator/*` is reachable on 8080 at all is therefore a fact about
`MANAGEMENT_ENDPOINTS_EXPOSURE`, not about this table.

**`OPERATOR` requires `X-Admin-Token` from `SHARD_ADMIN_TOKEN`, and binds every caller including
service-tier ones.** `GatewayRequestForwarder` demands a valid token — matched via
[`AdminTokenGuard`](../../src/main/java/com/recsys/application/auth/AdminTokenGuard.java) — for
`/api/catalog/setembedding`, the model version `activate`/`rollback`/`preload` endpoints, and
`/api/online/online/ops`. An API key is what every real caller already holds, so if it were
sufficient here the class would mean nothing: a denial returns
`403 {"error":"operator token required"}`, deliberately distinguishable from the 404 above — the
caller is being told to present a credential, not that the path is absent. The gateway now reads
`SHARD_ADMIN_TOKEN` from the `recsys-online-admin` Secret (`k8s/base/api-gateway.yaml`, mirroring
`k8s/base/online-serving.yaml`'s existing wiring for 7010) and warns at startup when it is unset;
unset means the guard authorizes nobody, so every `OPERATOR` route fails closed rather than open.

**`/shards` stays `AUTHENTICATED`, not `OPERATOR`, because the prefix mixes tiers.**
[`ShardedRecordService`](../../src/main/java/com/recsys/infrastructure/store/ShardedRecordService.java)
already calls `adminGuard.isAuthorized` itself on `POST /shards/topology` and `GET /shards/shard` —
the two operator sub-paths — but not on `GET /shards/device` or `POST /shards/records`, which are
ordinary per-entity data paths. Classifying the whole prefix `OPERATOR` at the gateway would break
those two reads and writes; classifying it `AUTHENTICATED` leaves the backend's own two-way split
as the actual enforcement, and the gateway's contribution is the same as for any other data-plane
route — requiring an authenticated caller, nothing more.

`LlmProxyService` remains the second forwarding path noted in §10: it never consults
`BackendRoutePolicy` for the request path, for any of the four classes. Its construction guard
refuses any LLM route that resolves to a known backend at all — `NO_PROXY` and `OPERATOR`
backends included, not merely ones declaring a `USER_SCOPED` route — so a misrouted
`LLM_SERVICE_URL` fails at startup rather than forwarding an operator or telemetry path unchecked.

Design: [gateway proxy route policy](../superpowers/specs/2026-08-05-gateway-proxy-route-policy-design.md).

## 12. The third-party identifier boundary

Everything above governs callers reaching *in*. This section covers the one place an identifier
goes *out*: PostHog feature flags.

`RecommendationService` gates cold-start recommendations on a dynamic flag, keyed per user so a
rollout can be gradual. Resolving that flag POSTs to `https://us.i.posthog.com/decide/?v=3`, and the
key it carries is the request's `userId`. That is the only path in the system that sends an
application user identifier to an external service — the exception to §2's rule that identity stays
inside the trust boundary.

**What PostHog receives is a pseudonym, not the userId.**
[`PostHogFeatureFlagProvider`](../../src/main/java/com/recsys/infrastructure/featureflags/providers/PostHogFeatureFlagProvider.java)
sends `sha256(salt + ":" + userId)` as `distinct_id` — the full digest. Deterministic and stable
across pods and restarts, because the salt is shared configuration, which is what lets percentage
rollouts bucket a user consistently. One-way, so PostHog holds nothing that identifies a user here.

**`POSTHOG_DISTINCT_ID_SALT` is required when PostHog is enabled**, with no default. A blank salt
fails provider construction, the same fail-closed shape as the blank-API-key check beside it.
Requiring it is not ceremony: userIds in this system are small integers, so an *unsalted* digest
over that key space is a rainbow table anyone can build in seconds. An unsalted hash would look
like a control and be none. The two alternatives are both worse — falling back to the raw id
defeats the point, and a per-process random salt makes bucketing differ per pod and per restart, so
gradual rollout breaks silently instead of loudly.

**Rotating the salt re-buckets every user.** A rollout in flight reshuffles who is inside it. Treat
the salt as long-lived configuration.

**What it costs.** Nobody can look a specific user up in PostHog any more, because PostHog no
longer knows about that user. That is the intended effect, and it removes a debugging path anyone
enabling the flag might expect to have.

**How dormant this is.** PostHog is disabled by default (`POSTHOG_FEATURE_FLAGS_ENABLED=false`),
additionally requires a non-blank API key before the provider is constructed at all, is set in no
manifest or overlay, and is permitted by no egress rule — `k8s/base/network-policy.yaml` allows no
egress to PostHog, an instance of sharp edge 9 above. On an enforcing CNI the request would fail at
the network layer before disclosing anything. The risk this section closes was never "we are
leaking user ids"; it was that two environment variables could turn a recommendation path into a
disclosure path with nothing in the code marking that as a decision.

Scope note: `person_properties` is empty at the one call site. If it ever carries user attributes,
that is a fresh disclosure decision and this section does not authorize it.

Design: [PostHog pseudonymous distinct_id](../superpowers/specs/2026-08-05-posthog-pseudonymous-distinct-id-design.md).
