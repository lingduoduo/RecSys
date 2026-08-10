# Data-Leakage Posture in Recsys-Backend-Service

An audit of what the data-leakage controls **actually enforce as deployed**, rather than
what was merged. Between 2026-08-05 and 2026-08-09, eleven PRs (#274–#284) shipped work
against four threat models: exfiltration by a compromised workload, cross-user leakage,
data reaching third parties, and accidental disclosure through responses, metrics and
logs. Every one of those PRs is closed. A list of merged PRs is not a security posture.

This document is derived from the code and from the manifests **as rendered**
(`kubectl kustomize k8s/base`, `k8s/eks`, `k8s/eks-us-west-2` at `123977a`), not from the
patch files and not from the prose in the runbooks. Overlays override, so a patch read in
isolation says nothing about what a cluster receives. Where a fact could not be
established from this repository, §7 says so rather than inferring it.

The scope boundary matters: this is a posture audit of manifests and code. It is not a
penetration test, nothing here was executed against a live cluster, and no claim below
should be read as evidence that a control behaves as described *at runtime* unless it
says so explicitly.

## 1. The short answer

**Three things would actually stop a data leak if these manifests were applied today**,
and all three are properties of code rather than of configuration: the Redis client
refuses to start without a password, the recommendation cursor is HMAC-signed with a key
the pod cannot start without, and the gateway strips caller credentials before proxying
upstream. Everything else on the list is either dormant, placeholder-shaped, or dependent
on a Secret or an AWS resource that this repository does not create.

**The single most consequential finding was not on the PR list at all — and it has since
been fixed.** `k8s/base` used to publish the API gateway as an `internet-facing` NLB
(`type: LoadBalancer` with `aws-load-balancer-scheme: internet-facing`) *and* set
`GATEWAY_ALLOW_ANONYMOUS: "true"`. Those two facts composed: a first cluster, a demo
environment, or any overlay that built on `../base` without also composing `../eks-shared`
got an internet-reachable gateway that authenticated nobody — and because
`GatewayAuthenticator` short-circuits when disabled, the `PROTECTED_PREFIXES` never-public
guard was not consulted either, so the routes that declared themselves protected were as
open as everything else. Anonymous callers could reach `/api/catalog/user`,
`/api/users/**`, `/api/features/**`, `/api/online/**` and every user-scoped recommendation
route with an arbitrary `userId`. Nothing in the repo read `GATEWAY_ALLOW_ANONYMOUS` at all
before this was found.

`k8s/base`'s gateway `Service` is now `ClusterIP`
([`k8s/base/api-gateway.yaml`](../../k8s/base/api-gateway.yaml)), with the eleven
`aws-load-balancer-*` annotations removed. `GATEWAY_ALLOW_ANONYMOUS` is still `"true"` in
base — flipping it was never the fix, since `GatewayAuthenticator.fromEnvironment` refuses
to start without a credential source and base has none — but the anonymous gateway is no
longer reachable from outside the cluster; reaching it now means
`kubectl port-forward svc/recsys-api-gateway 8010:80 -n recsys`. The pairing is now pinned
by `GatewayExposureManifestTest`: in `k8s/base`, an anonymous gateway must not be
`LoadBalancer`/`NodePort` or carry `aws-load-balancer-scheme: internet-facing`, and any
region overlay that exposes the gateway must also set `GATEWAY_ALLOW_ANONYMOUS: "false"`.
The test reads manifest text rather than rendering a kustomization, so the overlay half is
a coupling between texts, not a proof of what a cluster actually receives — see §5.

## 2. Control inventory

Twenty controls. For each: what it does, whether it is on in `k8s/base`, whether it is on
in the two EKS overlays as rendered, and what makes it inert.

Legend: **ON** = enforcing as rendered. **FAIL-CLOSED** = configured to deny, which
denies legitimate operators too. **OFF** = present but not enforcing. **N/A** = the
resource does not exist in that configuration.

| # | Control | What it does | `k8s/base` | `k8s/eks` + `k8s/eks-us-west-2` | What makes it inert |
|---|---|---|---|---|---|
| 1 | Redis client auth guard (#274) | `LettuceClientFactory` throws at startup rather than connecting to Redis as the unauthenticated `default` user | ON (guard active; `REDIS_ALLOW_NO_AUTH` set in no manifest) | ON | Nothing disables it in-cluster. But `REDIS_PASSWORD` comes from an `optional: true` `secretKeyRef` on `recsys-secrets`, and **no `kind: Secret` exists anywhere under `k8s/`** — without an out-of-band Secret every serving pod CrashLoops. The control is real; its failure mode is outage, not silent plaintext |
| 2 | Redis transport TLS (#274) | `REDIS_TLS=true` wraps the Lettuce connection in TLS (Lettuce's default `SslVerifyMode.FULL` — chain + hostname) | OFF (unset → code default `false`) | **OFF (explicitly `REDIS_TLS: "false"` in both overlays)** | Redis traffic — embeddings, device history, the login-token→API-key mapping — is plaintext on the wire in every rendered configuration. The overlay comment states the overlay must not be applied until ElastiCache has transit encryption |
| 3 | Redis server-side `requirepass` (#274) | In-cluster Redis StatefulSets pass `--requirepass $(REDIS_PASSWORD)` | ON, conditional on the Secret | **N/A** — `k8s/eks-shared` scales `redis-primary`/`redis-replica`/`redis-sentinel` to `replicas: 0`; every client dials ElastiCache instead | In EKS the flag governs pods that do not run. ElastiCache AUTH is out-of-band and unverifiable from here |
| 4 | Redis per-workload ACL users (#284) | Six users (`default`, `catalog`, `model`, `online`, `gateway`, `reconciliation`) with per-key-prefix grants, loaded via `--aclfile`; each workload sends `REDIS_USERNAME` | Conditional | **INERT** — `--aclfile` is an argument to StatefulSets scaled to zero | `k8s/base/redis-users.acl.template` is a *template* with `__X_PASSWORD__` placeholders. It is mounted from a **non-optional** Secret volume (`recsys-secrets`, key `redis-users.acl`), so without that Secret the Redis pods block in `ContainerCreating`. In EKS the equivalent is ElastiCache RBAC user groups, which nothing in this repo creates — yet the workloads still send `REDIS_USERNAME`, so an ElastiCache without those users fails AUTH outright |
| 5 | Gateway authentication | API key (`x-api-key`/bearer) or Cognito JWT, else 401 | **OFF — `GATEWAY_ALLOW_ANONYMOUS: "true"`, no `GATEWAY_API_KEYS`** | ON — `GATEWAY_ALLOW_ANONYMOUS: "false"` and `GATEWAY_API_KEYS` from `recsys-gateway-auth` with `optional: false` (pod will not start without it) | In base, `GatewayAuthenticator.fromEnvironment` returns the `DISABLED` instance; `check` then allows every path unconditionally |
| 6 | Never-public prefix guard | `PROTECTED_PREFIXES` overrides `GATEWAY_PUBLIC_PATHS` so user-data routes can never be listed public | OFF (only consulted when authentication is enabled) | ON | Rides entirely on #5 |
| 7 | User-scope authorization (#275) | A JWT caller may only name its own `userId`; 403 + `gateway_user_scope_rejected_total` otherwise | **INERT** | **INERT** | `GATEWAY_COGNITO_ISSUER: ""` in all three renders, and blank is treated as unset. With no verifier, no principal can ever be `Tier.USER`; API-key and anonymous callers are `Tier.SERVICE` and the check returns immediately. Confirmed dormant, exactly as recorded |
| 8 | What authorizes user-scoped routes instead | — | **Nothing.** `userId` is client-supplied in the query string or body on every user-scoped route | Same | This is the honest answer to "what guards user data in the meantime": an authenticated API-key caller may name any `userId`, and in base an unauthenticated one may too |
| 9 | Gateway operator token (#276, #277) | `BackendRoutePolicy` classifies four backend paths `OPERATOR`; `GatewayRequestForwarder` requires a matching `X-Admin-Token`, tier-independent | FAIL-CLOSED | FAIL-CLOSED | `SHARD_ADMIN_TOKEN` comes from `recsys-online-admin` with `optional: true`, and that Secret is not in the repo. Unset ⇒ 403 for **every** caller on `/api/catalog/setembedding`, the three model version endpoints and `/api/online/online/ops`. The gateway logs a startup warning. Correctly fail-closed; also currently unusable |
| 10 | Online-serving operator token | `AdminTokenGuard` on `/online/ops`, `POST /shards/topology`, `GET /shards/shard` | FAIL-CLOSED | FAIL-CLOSED | Same Secret. Note the coverage is narrower than the gateway's: `GET /shards/device` and the record write path are unguarded, and 7010 logs **no** startup warning |
| 11 | Origin secret | `x-origin-secret` on a server-wide decorator; 403 + counter otherwise; `/health` and `/metrics` exempt | **OFF** | **OFF** | `GATEWAY_ORIGIN_SECRET` is an `optional: true` `secretKeyRef` on `recsys-gateway-origin-secret`, absent from the repo. When blank the decorator is not registered at all. This is the only server-wide gate on the gateway, and it is off |
| 12 | Credential stripping | The gateway removes `authorization`, `x-api-key` and `x-origin-secret` before forwarding upstream | **ON** | **ON** | Unconditional in code. One of the few controls with no configuration dependency |
| 13 | PostHog pseudonymization (#278) | Sends `sha256(salt + ":" + userId)` as `distinct_id`; blank salt fails construction | **INERT** | **INERT** | `POSTHOG_FEATURE_FLAGS_ENABLED` defaults `false` and is set in no manifest, compose file, or overlay, so the provider is never constructed. The code path *is* wired (model serving → `RecommendationService` → cold-start flag), so it is not dead code — but no NetworkPolicy egress rule permits reaching PostHog either. Confirmed dormant and unrouted |
| 14 | Pagination cursor signing (#279) | HMAC-SHA256 over `(version, issuedAt, userId, queryFingerprint, score, itemId)`; unsigned/tampered/expired/mismatched ⇒ 400 | **ON** | **ON** | `RECOMMENDATION_CURSOR_SIGNING_KEY` is a **non-optional** `secretKeyRef` on all three serving workloads and the config rejects a key under 32 bytes, so a pod cannot start with signing off. `RECOMMENDATION_CURSOR_ACCEPT_LEGACY: "false"` closes the unsigned legacy format. Genuinely enforced — but see §3 for what it is and is not worth |
| 15 | MySQL transport TLS (#280) | `MySqlConnectionSettings` refuses a `MYSQL_URL` without `sslMode=VERIFY_IDENTITY`, loopback exempt | **INERT** | **INERT** | `MYSQL_ENABLED: "false"` in all three renders, and the validation sits entirely behind that flag. No `mysql` Service or StatefulSet exists in `k8s/` at all. There is also a documented residual bypass: the guard does not percent-decode parameter names, so `?sslMode=VERIFY_IDENTITY&%73slMode=DISABLED` passes the guard and resolves to `DISABLED` in Connector/J |
| 16 | NetworkPolicy ingress lockdown | Restricts 6010/7010/8080 to the `recsys-api-gateway` pod selector (+ Prometheus) | Present | Present | **Enforcement is CNI-dependent and nothing in this repo establishes it.** There is no IaC of any kind — no Terraform, CDK, eksctl config, no Calico/Cilium install, no `ENABLE_NETWORK_POLICY`. EKS's default VPC CNI does not enforce NetworkPolicy unless explicitly enabled. Also: the gateway's own ingress rule on 8010 has no `from`, so any pod in any namespace may reach it |
| 17 | NetworkPolicy egress (#273, #283) | Denies unlisted egress on the four serving workloads; DNS scoped to `kube-system` | Present | Present, plus a `10.0.0.0/16` ElastiCache `ipBlock` marked `REPLACE_ME` | Same CNI dependency. Gaps that survive: there is **no default-deny**, the `recsys-outbox-reconciliation` CronJob is selected by no policy at all (unrestricted both directions), and `recsys-outbox-relay`, `redis` and `redis-sentinel` declare `policyTypes: [Ingress]` only. The relay's unrestricted egress is *pinned by a test as intentional* |
| 18 | CDN viewer-request normalization (#282) | Whitelists four URIs and their parameters, rejects multi-value and any `%`-containing value with 400, canonicalizes parameter order | **N/A** | **N/A** | `scripts/cdn/normalize-catalog-query.js` is deployed only by the manual `scripts/create-cdn-distribution.sh`; no workflow invokes it and the repo states no distribution exists in the account. Neither base nor the overlays reference CloudFront |
| 19 | CDN WAF WebACL | ALB Ingress annotation attaching a regional WebACL | **N/A** — base has no Ingress | Present but placeholder: `...:123456789012:regional/webacl/recsys-api-gateway/REPLACE_ME` | No WebACL rules exist anywhere in the repo. The repo's comments claim the ALB Controller rejects an invalid ARN at apply time; nothing here verifies that claim, and if it is wrong the failure mode is an internet-facing ALB with no WAF |
| 20 | Splunk log shipping | Ships structured JSON log events to HEC; bounded, drop-on-full, at-most-once | **OFF** | **OFF** | `SPLUNK_HEC_TOKEN` is an `optional: true` `secretKeyRef` on `recsys-splunk`, which is not in the repo, and no `splunk` Service is deployed either. Unset ⇒ the appender installs and starts but allocates no queue and no drain thread. Because delivery is at-most-once by design, a Splunk search is a lower bound on what was logged even when it *is* on |

Two further controls are worth naming because a reader may assume they are load-bearing:

- **Submit-token CSRF** (`RECSYS_SUBMIT_TOKEN_ENABLED`) defaults `false` and is set in no
  manifest. Even enabled it is not an authorization control — the token is obtainable
  anonymously from `GET /api/v1/token`.
- **Log PII redaction does not exist.** Nothing masks `userId` in log lines. The three
  redaction helpers in the tree scrub HEC tokens from error text, JDBC credentials from a
  URL, and feature-map keys in the Kafka path — none touches log events, and the Splunk
  serializer copies every MDC entry verbatim. Concrete userIds do reach log lines in the
  A/B exposure path.

## 2.1 Where the two EKS overlays differ from each other

They do not differ on any control in this table. `k8s/eks-us-west-2` differs only in
region (`AWS_REGION`, ECR registry, ElastiCache endpoint, WAF ARN) and HPA minimums. Both
compose the same `k8s/eks-shared` component, so both get `GATEWAY_ALLOW_ANONYMOUS=false`,
`GATEWAY_API_KEYS` from a required Secret, `REDIS_TLS: "false"`, and Redis scaled to zero.
`k8s/eks-us-west-2-active` layers only an HPA capacity patch on top and changes nothing
security-relevant.

## 3. What is enforced today, and what is inert

### 3.1 Enforced

**Redis client authentication (#274).** `LettuceClientFactory.requireAuthentication`
throws unless a non-blank password is present or `REDIS_ALLOW_NO_AUTH` is explicitly
true, with no host-based exemption. The escape hatch is set only in Surefire config and
`scripts/run-microservices-local.sh` — never in a manifest, and a test walks all of
`k8s/**` to keep it that way. This is a real, unconditional guard.

Two caveats that matter. First, it protects the *client's* posture, not the server's: it
guarantees this service will not speak to Redis unauthenticated, not that Redis rejects
anyone else. Second, two Redis clients bypass the factory — the Flink streaming job and
the Spark item-embedding job — and the Flink sinks write the very feature, embedding and
top-K keys the serving path reads.

That second caveat has narrowed but has **not** closed. Both jobs used to call
`RedisClient.create(RedisURI.create(host, port))` with no username, no password and no
TLS, so they *could not* authenticate at all. They now build the URI through
`com.recsys.infrastructure.redis.StreamingRedisUri`, which delegates to the same
`LettuceClientFactory.standaloneUri` every service uses, and each job reads
`redis.username` / `redis.password` / `redis.tls` (Spark: `--redis-username` /
`--redis-password` / `--redis-tls`), defaulting to `REDIS_USERNAME` / `REDIS_PASSWORD` /
`REDIS_TLS`. See `docs/runbooks/redis-auth.md`.

What remains is the whole of the risk. **Nothing in this repository submits either job**,
so no credential is configured anywhere here; the ability to authenticate is not the same
as authenticating. If `requirepass` is deployed and whoever submits these jobs does not
supply those parameters or those environment variables, both jobs still fail `NOAUTH` —
and the failure lands on the keys the serving path reads. The outage risk is unchanged
until the submitting configuration is updated, which cannot be verified from here.

Neither call site is verified by the default build: `online/flink/` and
`training/rulebased/` are excluded from the Maven compile, so `mvn package` and the
`resilience` gate never compile either file. The credential-building logic they call is in
the compiled tree and is covered by `StreamingRedisUriTest`; the calls themselves are not
covered by any gating test. They do compile under the opt-in `-Pstreaming-flink` and
`-Poffline-embedding` profiles, neither of which CI runs. So "every Redis client
authenticates" is still not a statement this codebase supports — it is now a statement
about the *deployment* of two jobs rather than about their code.

**Cursor signing (#279).** Enforced in a way few controls here are: the signing key is a
non-optional `secretKeyRef` on all three serving workloads and the config rejects a short
key, so there is no configuration in which a pod runs with signing disabled. Be precise
about what it buys, though. The cursor is *bound* to a `userId` that the client already
supplies in the request body, so a forged cursor does not read another user's data — it
was never the authorization boundary. What signing actually prevents is arbitrary keyset
repositioning, replay of an expired position past the 900-second window, and replay of a
position across a different exclusion set. It is a pagination-integrity control, not a
cross-user one.

**Credential stripping.** Unconditional, no configuration dependency.

**Operator-token fail-closure (#276, #277).** Genuinely fail-closed: with
`SHARD_ADMIN_TOKEN` unset, `AdminTokenGuard` denies everyone, constant-time, and the
gateway logs a warning at startup. The tier authorizes nobody today. That is the correct
default, and it means the control cannot currently be *used* either.

### 3.2 Inert

`REDIS_TLS` (off, and explicitly `"false"` in the overlays). Redis ACL users in EKS
(argument to a zero-replica StatefulSet). User-scope authorization (no Cognito issuer
anywhere). PostHog pseudonymization (flag off, provider never constructed, egress not
permitted). MySQL TLS (`MYSQL_ENABLED=false`, validation never reached). Origin secret
(Secret absent, decorator not registered). Splunk (token absent, no Splunk deployed). CDN
function and WAF (no distribution, placeholder ARN). Gateway authentication *in base*.

### 3.3 Who could reach what, concretely

**`k8s/base` applied to a real cluster** (assuming an operator supplies a `recsys-secrets`
carrying the cursor signing key and `redis-users.acl`, without which nothing starts at
all): the gateway `Service` is `ClusterIP`, so nothing on the internet reaches it. Whoever
*can* reach it — another pod in the cluster, or an operator running
`kubectl port-forward svc/recsys-api-gateway 8010:80 -n recsys` — still hits a gateway that
authenticates nobody: every proxied route including `/api/catalog/user`, `/api/users/**`,
`/api/features/**` and every user-scoped recommendation route with an arbitrary `userId`,
plus the gateway's unauthenticated `/metrics`. The only things they cannot reach are the
four `OPERATOR`-classified routes, which 403 for everyone. Redis traffic is plaintext.
Nothing is shipped to Splunk.

**Either EKS overlay applied** — which its own comments say must not happen until
ElastiCache has transit encryption and an AUTH token, because there is no working
combination of the rendered values: an API key is required to reach anything, and an
API-key caller is `Tier.SERVICE` and unrestricted, so **any valid API key can read any
user's data by naming their `userId`**. The operator routes still 403 for everyone.

**From inside the cluster, in any configuration**, the backends authenticate nobody.
Catalog serving (6010), online serving (7010) and model serving (8080) apply no
authentication to their serving routes, do not validate any header the gateway sets, and
Spring Security is not on the classpath. `/setembedding` on 6010 — which the gateway
classifies `OPERATOR` — is completely open to any pod that can reach port 6010. The only
thing separating them from a compromised or mislabelled pod is the NetworkPolicy, whose
enforcement is unestablished (§16), and which does not cover the reconciliation CronJob at
all.

## 4. The base-versus-overlay gap

`k8s/base` is still weaker than the EKS overlays on one control, but the composition that
used to make it severe no longer holds.

| | `k8s/base` | EKS overlays |
|---|---|---|
| `GATEWAY_ALLOW_ANONYMOUS` | `"true"` | `"false"` |
| `GATEWAY_API_KEYS` | absent | required Secret, `optional: false` |
| Gateway Service | `ClusterIP` | `ClusterIP`; ALB Ingress is the sole entry, gated by `GATEWAY_ALLOW_ANONYMOUS: "false"` |

An anonymous gateway is a reasonable local-dev default on its own — it is what lets base
run without a Cognito pool or key Secret. What made it dangerous was pairing it with
`type: LoadBalancer` and `aws-load-balancer-scheme: internet-facing`: together, in the
configuration someone would most plausibly reach for first, they published an
unauthenticated gateway to the internet. That pairing is gone — base's gateway `Service` is
now `ClusterIP`, matching the overlays, and `GatewayExposureManifestTest` (§5) keeps it
that way. Base is still the weaker configuration on authentication itself — anonymous, no
API keys — but that weakness is now scoped to whoever can reach the cluster network or hold
a `kubectl` credential, not to the internet.

Every other control is equal or worse in the overlays, not better: `REDIS_TLS` is set to
`"false"` explicitly there rather than merely defaulting to it, and the Redis ACL file
becomes an argument to StatefulSets scaled to zero.

## 5. Conformance tests that pass green while the control is off

Every gating security test in this repository is a file-shape test. The PR gate runs
`mvn -Presilience test`, an allow-list profile, and `<excludedGroups>load,docker</...>`
strips the `docker` tag regardless of any include — so every test that exercises a real
Redis, MySQL, Splunk or CDN is structurally incapable of blocking a merge. What remains
parses YAML and Java source text.

The specific false-assurance cases:

- **`MySqlTlsManifestTest`** asserts `MYSQL_URL` contains `sslMode=VERIFY_IDENTITY` in
  `k8s/base`. It never reads `MYSQL_ENABLED`, which is `"false"`. The test is green and
  the control is unreachable. Its own javadoc also notes the parser is a hand-copy of the
  production tokenizer, so a flaw in the rule is invisible to both.
- **`OperatorTokenManifestTest`** (#277) requires the `secretKeyRef` to set
  `optional: true` — deliberately, so a cluster without the Secret degrades to 403 rather
  than failing to schedule. It therefore asserts the *reference*, never the Secret. There
  is no `kind: Secret` anywhere under `k8s/`. Green, with the tier authorizing nobody.
- **`RedisAclManifestTest`** (#284) asserts the template's passwords are *placeholders* —
  i.e. it asserts the real credential is absent. Nothing renders the template into a
  Secret, nothing checks ElastiCache RBAC, nothing checks the file is loaded by a running
  server.
- **`RedisAuthManifestTest`** asserts `--requirepass` on StatefulSets that the EKS
  overlays scale to zero.
- **`NetworkPolicyEgressManifestTest`** (#273/#283) is thorough about rule shape,
  including a both-ends check and the kube-system DNS scoping. It cannot assert
  enforcement, and its own failure messages say "under an enforcing CNI". It also pins the
  outbox relay's unrestricted egress as intentional, and asserts nothing about the
  uncovered reconciliation CronJob or the `from`-less gateway ingress rule.
- **`SplunkLogbackWiringTest`** contains an assertion that *requires the control to be
  off* — it `assumeTrue`s that `SPLUNK_HEC_TOKEN` is unset, then asserts the counters are
  zero. That is a correct test of inertness, but it is green precisely in the state where
  no logs ship.
- **`CdnQueryNormalizationConformanceTest`** compares two committed files to each other. A
  cache policy edited by hand in the console is invisible to it, and no distribution
  exists to be compared against.

**`GatewayExposureManifestTest` now asserts the pairing that made the base finding
severe.** In `k8s/base`, an anonymous gateway must not be `LoadBalancer`/`NodePort` or
carry `aws-load-balancer-scheme: internet-facing`; and any region overlay that exposes the
gateway — via `alb.ingress.kubernetes.io/scheme: internet-facing` on its WAF Ingress, which
is how it is actually done here, not the Service-type NLB annotation — must also set
`GATEWAY_ALLOW_ANONYMOUS: "false"`. Before this test existed, no test asserted
`GATEWAY_ALLOW_ANONYMOUS` anywhere; the only Java occurrences were in-memory env maps in
`GatewayAuthenticatorTest`, which proves the code fails closed without asserting anything
about a manifest.

That coverage has a real limit, stated rather than implied: **the test reads files, it does
not render a kustomization**, so the overlay half is a coupling between texts, not proof
that a kustomization applies as written, and it cannot see a patch that fails to apply.
Both of this week's overlay defects reached `main` through exactly that hole — an
exposure check that at first only recognized the Service-type NLB annotation and missed the
ALB-Ingress annotation the region overlays actually use, and a per-directory scan that
could not see `GATEWAY_ALLOW_ANONYMOUS: "false"`, which lives only in the `eks-shared`
component pulled in via `components:`, not in either region overlay's own files. A test
that renders each overlay would subsume this one and would need the `kustomize` binary on
CI.

`GatewayOriginSecretTest` is not in the `resilience` allow-list at all — the origin
lockdown control has no gate coverage in either direction.

## 6. What a reader should not assume from the PR list

1. **"Redis transport auth shipped" does not mean Redis traffic is encrypted.** #274
   shipped a password guard. `REDIS_TLS` is `false` in every rendered configuration, and
   the EKS overlays set it to `"false"` deliberately while stating the overlay must not be
   applied.
2. **"Redis ACL users shipped" does not mean per-workload least privilege is in force
   anywhere it matters.** In EKS the `--aclfile` belongs to StatefulSets scaled to zero.
3. **"Gateway authentication is fail-closed" is a property of the code, not of
   `k8s/base`.** Base still opts out explicitly — `GATEWAY_ALLOW_ANONYMOUS: "true"` remains
   the deliberate local-dev default — but it no longer pairs that opt-out with an
   internet-facing load balancer: the gateway `Service` is `ClusterIP`, and
   `GatewayExposureManifestTest` keeps it that way.
4. **"User-scope authorization shipped" does not mean user data is scoped.** It is dormant
   in all three renders, and in the meantime *nothing* constrains which `userId` a caller
   may name.
5. **"Operator token enforced at the gateway" currently means every operator route rejects
   every caller.** That is the intended fail-closed default, not a working control.
6. **"NetworkPolicy egress conformance" proves rule shape, not enforcement.** Nothing in
   this repo establishes that the CNI enforces NetworkPolicy — and the entire argument for
   why the backends need no authentication of their own rests on that unverified
   assumption.
7. **"CDN edge controls" describes a distribution that does not exist**, guarded by a WAF
   ARN that is the literal string `REPLACE_ME`.
8. **A green PR gate says nothing about any of this.** Every gating security test reads
   files; every runtime-enforcement test is `docker`-tagged and excluded.
9. **Splunk being "shipped" does not mean there is an audit trail.** The token is unset
   everywhere, no Splunk is deployed, and even when enabled delivery is at-most-once, so a
   search is a lower bound. Nothing redacts userIds from what would be shipped.

## 7. What could not be established

- **Whether the CNI in any target cluster enforces NetworkPolicy.** There is no IaC in
  this repository. This is the single assumption on which the largest number of other
  claims depend.
- **Whether any of the required out-of-band Secrets exist** (`recsys-secrets`,
  `recsys-gateway-auth`, `recsys-online-admin`, `recsys-gateway-origin-secret`,
  `recsys-splunk`). None is in the repo; their existence is not observable from here. Note
  the asymmetry in consequence: an absent `recsys-gateway-auth` blocks the EKS gateway pod
  from starting, an absent `recsys-online-admin` silently disables the operator tier, and
  an absent `redis-users.acl` key blocks the base Redis pods in `ContainerCreating`.
- **Whether ElastiCache has transit encryption, an AUTH token, or RBAC user groups
  configured** in either region, and whether the `10.0.0.0/16` ElastiCache CIDR is
  correct. All out-of-band.
- **Whether the ALB Controller actually rejects a nonexistent WAF ARN.** The repo asserts
  it in comments; nothing verifies it.
- **How the Flink and Spark jobs are deployed**, and therefore whether anyone passes them
  the Redis credentials they can now accept. The code no longer prevents them from
  authenticating; nothing here shows that they do.
- **Whether the CloudFront viewer-request function receives raw or normalized percent
  encoding.** The function's own header says only a real distribution distinguishes the
  two and none exists.
- **Any claim about a running cluster.** Nothing in this audit was executed against one.
