# MySQL transport TLS

Require a verified TLS connection to MySQL, so an enabled database cannot be reached over
plaintext without anyone noticing.

## The gap

Three places agree that the MySQL connection has no transport requirement:

- `k8s/base/configmap.yaml` — `MYSQL_URL: "jdbc:mysql://mysql:3306/recsys"`, no TLS parameters.
- `src/main/resources/application.yml` — the local default explicitly sets `useSSL=false`.
- `MySqlConnectionSettings` — validates the password and the cursor signing key when MySQL is
  enabled, and says nothing about transport.

Connector/J 8.4 defaults to `sslMode=PREFERRED`, which negotiates TLS **if the server offers it**,
verifies no certificate, and **falls back to plaintext silently** if it does not. From the
application's side a plaintext connection is indistinguishable from an encrypted one: no error, no
warning, no metric.

This is the same class as the finding the 2026-08-05 audit ranked first and shipped as PR #274 —
a data-tier connection with no enforced transport security. `LettuceClientFactory` now refuses an
unauthenticated Redis connection; `MySqlConnectionSettings` has no equivalent.

**How dormant it is.** `MYSQL_ENABLED` defaults to `"false"` in `k8s/base/configmap.yaml`, and
`TransactionalMySql` refuses to open a connection while disabled. No manifest in this repo deploys
MySQL at all — `k8s/base/network-policy.yaml` has an egress rule to `app: mysql` on 3306 for a pod
nothing here creates. So today nothing connects. The risk is that enabling the durable
eventual-consistency feature turns on a plaintext database connection as a side effect, with
nothing marking that as a decision.

## Scope

In scope: the transport requirement on the MySQL connection, when MySQL is enabled.

Out of scope:

- Encryption at rest. Nothing this repo deploys persists data in EKS — the only `volumeClaimTemplates`
  is Redis's, scaled to zero in every EKS region — so at-rest encryption is a property of ElastiCache
  and of whoever provisions MySQL, neither visible here.
- Provisioning MySQL, or its certificate. This design states a requirement; it does not create the
  server that must satisfy it.
- The other data-tier credentials. `MYSQL_PASSWORD` is already required when MySQL is enabled, and
  Redis shipped in #274.

## The rule

**When MySQL is enabled, the effective `sslMode` must be `VERIFY_IDENTITY`.**

`VERIFY_IDENTITY` rather than `REQUIRED` because `REQUIRED` encrypts without verifying, which stops
silent plaintext but not an active man-in-the-middle.

It costs no extra provisioning if MySQL is RDS, whose certificates chain to Amazon Root CAs that a
modern JVM truststore already carries. The repo has a precedent for relying on that property, though
for a different service: `k8s/eks/redis-elasticache-patch.yaml` records the same reasoning for
verifying **ElastiCache** against the default truststore. It says nothing about RDS — the parallel
is the argument, not the citation.

Against a self-hosted server with a self-signed certificate it fails immediately — an honest failure
that says the connection was never protected.

Rejected: `sslMode` **absent** (the `PREFERRED` default is the whole finding), `DISABLED`,
`PREFERRED`, `REQUIRED`, `VERIFY_CA`. If `sslMode` appears more than once, every occurrence must be
`VERIFY_IDENTITY` — Connector/J picks one and the reader cannot tell which, so agreement is the only
safe rule.

A URL carrying the deprecated `useSSL` is rejected too, with a message directing the operator to
`sslMode`. Where both appear, `sslMode` silently wins — so a URL containing both reads as one thing
and behaves as another. Refusing the ambiguity is cheaper than resolving it.

## Where the guard lives

**In `MySqlConnectionSettings`'s compact constructor**, beside the two validations already there:

```java
if (enabled && password.isEmpty()) { … }
if (enabled && cursorSigningKey…) { … }
```

The TLS check becomes the third, in the same place and the same style, and it inherits the
`enabled &&` guard that makes all of this inert while `MYSQL_ENABLED=false`.

This corrects an earlier draft of this design, which put the guard on `MySqlConnectionSettings.fromEnv()`.
That would have been wrong: `CatalogComponent.fromEnvironment()` calls `fromEnv()` **before**
checking `settings.enabled()`, so the guard would have thrown on every service running the default
configuration, where MySQL is disabled. The compact constructor is both the consistent home and the
correct one.

**Loopback exemption.** The **entire** transport requirement — the `sslMode` rule and the `useSSL`
rejection alike — is skipped when the host is `localhost`, `127.0.0.1` or `[::1]`, with one INFO
line recording it. Skipping both matters rather than being tidy: `application.yml`'s local default
URL is `jdbc:mysql://localhost:3306/recsys?useSSL=false&…`, so an exemption that covered only
`sslMode` would still reject the configuration this repo ships for local development. This is not an opt-out: it is scoped to a connection with
no network segment to intercept, and it cannot be reached in Kubernetes, where the host is `mysql`
or an RDS endpoint. That property is the point — the Redis finding existed because an opt-out flag
was settable and unset everywhere, and an exemption nobody can misconfigure is a better shape than
a flag with a warning attached.

Testcontainers exposes MySQL on `localhost` with a mapped port, so integration tests are covered by
the exemption rather than by any test-only affordance.

**On the package-private test seam:** an earlier draft included one, on the `LettuceClientFactory`
precedent. It is dropped. Because the guard sits in the compact constructor, every construction path
crosses it, so a seam would mean a second construction route that skips validation — which is the
thing conformance work in this repo keeps removing. The loopback exemption covers both test shapes
that exist, so the seam has no remaining job.

## Manifest and conformance test

`k8s/base/configmap.yaml` gains `?sslMode=VERIFY_IDENTITY` on `MYSQL_URL`.

`MySqlTlsManifestTest` in `com.recsys.infrastructure.k8s`, mirroring `RedisAuthManifestTest`: every
manifest setting `MYSQL_URL` must specify `sslMode=VERIFY_IDENTITY`, and none may set a weaker mode
or carry `useSSL` — the analogue of that test's `noManifestOptsOutOfRedisAuthentication`.

The test earns its place separately from the code guard: the guard protects a running service, and
only a manifest test catches a URL that would otherwise fail at deploy time rather than review time.
It goes in the `resilience` profile, which is what the PR gate runs.

## Consequences

**This makes the base manifest un-applyable against a plaintext MySQL, once MySQL is enabled.** If
the server does not serve TLS with a certificate the JVM truststore accepts, `MySqlConnectionSettings`
throws and the services that build a catalog component refuse to start. Not degrade — refuse.

That is the intended behavior of a fail-closed guard and it has precedent: the Redis work left the
EKS overlays deliberately un-applyable until ElastiCache has transit encryption. This is the same
bargain against the same class of risk. It is bounded by `MYSQL_ENABLED=false` being the default, so
nothing changes until someone turns the feature on — which is exactly the moment the decision should
surface.

**It cannot be verified end to end.** No manifest here deploys MySQL, so the guard, the URL and the
test can all be correct while nothing proves a real server accepts a `VERIFY_IDENTITY` connection.
Whoever provisions that database finds out first. Same shape as the Splunk and Cognito work, and
stated here so a green suite is not mistaken for a working connection.

## Testing

- A settings instance with `enabled=true` and a URL lacking `sslMode` is rejected, and the message
  names `sslMode=VERIFY_IDENTITY`.
- `DISABLED`, `PREFERRED`, `REQUIRED` and `VERIFY_CA` are each rejected; `VERIFY_IDENTITY` is
  accepted.
- A URL carrying `useSSL` is rejected even when it also carries `sslMode=VERIFY_IDENTITY`.
- `enabled=false` with a plaintext URL constructs fine — the disabled case must stay inert, and this
  is the assertion that would have caught the `fromEnv()` mistake above.
- A `localhost`, `127.0.0.1` and `[::1]` URL with no `sslMode` constructs fine while enabled.
- The exact local default from `application.yml` — a `localhost` URL carrying `useSSL=false` —
  constructs fine while enabled, so the configuration this repo ships for local development keeps
  working.
- A non-loopback host with no `sslMode` is rejected, so the exemption is scoped rather than
  effectively universal.

## Documentation

- `docs/system_design/20_AuthN_AuthZ.md`, in the existing "Data-tier authentication" section beside
  the Redis credential: MySQL requires `sslMode=VERIFY_IDENTITY` when enabled, the loopback
  exemption and why it is not an opt-out, and the refuse-to-start consequence.
- `.claude/CLAUDE.md`: the `MYSQL_URL` entry notes the required `sslMode`.
