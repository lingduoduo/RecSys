# AuthN / AuthZ in Recsys-Backend-Service

An investigation of who proves their identity, where that proof is checked, and what
the proof entitles them to. The short answer: **authentication happens once, at the
gateway**, and everything behind it trusts the gateway. Authorization is deliberately
coarse — there is exactly one privilege tier above "authenticated caller", and it
guards operator surfaces on online serving.

## The big picture

Six distinct credentials exist. Only the first two authenticate an external caller;
the rest are narrower gates or receipts.

| Credential | Header | Checked by | Failure | Default |
|---|---|---|---|---|
| Gateway API key | `X-API-Key` / `Authorization: Bearer` | `GatewayAuthenticator` | `401` | unset → fail closed |
| Cognito JWT (RS256) | `Authorization: Bearer` | `CognitoJwtVerifier` | `401` / `403` | unset |
| CloudFront origin secret | `x-origin-secret` | `GatewayOriginSecret` | `403` | unset → disabled |
| Operator token | `X-Admin-Token` | `AdminTokenGuard` | `403` | unset → fail closed |
| Submit token (one-time CSRF) | `X-Submit-Token` | `SubmitTokenService` | `403` | disabled |
| Login session token | `Authorization: Bearer` | `LoginInterceptor` | envelope error | disabled |

Three structural facts shape everything below:

- **The gateway is the only front door.** `k8s/base/network-policy.yaml` restricts
  ingress on 6010/7010/8080 to the `recsys-api-gateway` pod selector; catalog and
  model serving additionally admit Prometheus from the `monitoring` namespace for
  their `/metrics` scrape, and online serving admits nothing else. The backends'
  lack of their own authentication is a deliberate consequence, not an oversight.
- **There is no authorization *model*.** No roles, no scopes, no per-resource
  ownership checks. A caller is authenticated or not; the single exception is the
  operator token in §5.
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

- **`PROTECTED_PREFIXES`** (`/api/catalog/user`, `/api/users`) is consulted **first**
  in `isPublic`, so those paths are never anonymous regardless of configuration.
- **`warnOnProtectedOverlap`** logs at startup when a configured public path would
  have exposed a protected prefix — the guard silently saving you is itself a
  misconfiguration worth fixing.

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

## 5. Operator authorization — the one privilege tier

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

## 8. Testing

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

## Sharp edges — notes

1. **Authentication is binary; there is no authorization model.** Outside the 7010
   operator token, any authenticated caller can reach every routed data-plane path —
   including control-plane writes such as `/api/catalog/setembedding` (overwrite item
   embeddings on 6010) and `/api/model/api/v1/model/versions/activate` and
   `/rollback` (swap the serving model). Those sit in the same privilege tier as a
   catalog read. The trust model is "callers are trusted backends", so this is
   consistent — but it means an API-key leak is a control-plane compromise, not just
   a data-plane one.
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
