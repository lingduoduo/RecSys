# Redis transport authentication — design

Every service in this repo connects to Redis as the unauthenticated `default` user, over an
unencrypted socket, and the client has no capability to do otherwise.

`LettuceClientFactory` reads `REDIS_PASSWORD` and calls `withPassword` when it is set. No manifest
in `k8s/base`, `k8s/eks-shared`, `k8s/eks`, or `k8s/eks-us-west-2` sets it. A case-insensitive
search of that file for `ssl`, `tls`, or `rediss` returns nothing: there is no TLS support to
enable, and `k8s/eks/redis-elasticache-patch.yaml` points at plaintext 6379.

What is on the other side of that socket: item and user embeddings (`i2vEmb:`, `u2vEmb:`), device
history and the sharded record store (`sr:*`), trending (`topk:*`), the service registry
(`svc:registry:*`), and — per sharp edge 3 of [20_AuthN_AuthZ](../../system_design/20_AuthN_AuthZ.md)
— the plaintext API key at `login:<token>`.

Two consequences follow. Anything with network reach to the endpoint reads all of it, with the
NetworkPolicy as the only control (and that control is CNI-dependent, per sharp edge 8). And
enabling ElastiCache encryption-in-transit would break all four services, because it is a code
change here rather than a configuration toggle there.

This design adds the client capability and turns on authentication where Redis actually runs.

## Scope

In: TLS and username support in the client, `requirepass` on the in-cluster Redis, a fail-closed
startup guard, and a conformance test.

Out, each for its own reason:

- **In-cluster TLS.** Certificate issuance and rotation, `--tls-port`, sentinel TLS, and probe
  changes are a larger project, and the NetworkPolicy already limits which pods can reach those
  ports. The client capability lands here so that project is config, not code.
- **Per-service Redis ACL users.** Already on the backlog. `REDIS_USERNAME` support here is
  precisely what unblocks it — the remaining work becomes server config plus manifests.
- **MySQL credential handling.** Separate data tier, separate change.

## The client

`LettuceClientFactory` has exactly two URI construction points, so the change is contained.

```
standaloneUri(host, port, username, password, tls, timeoutMs)
sentinelUri(master, nodes, username, password, tls, timeoutMs)
```

- Username non-blank → `withAuthentication(username, password)`. Otherwise the existing
  `withPassword` path, unchanged.
- `tls` → `withSsl(true)`, leaving peer verification at Lettuce's default: full verification
  against the JVM truststore.

**No insecure-TLS escape hatch.** The only TLS destination in scope is ElastiCache, whose
certificates chain to Amazon Root CA, which is already in the default truststore. `SPLUNK_HEC_INSECURE_TLS`
exists as precedent, but adding a verification bypass before the feature has a single user is how
bypasses become permanent.

Two new environment variables, read in `uriFromEnv`: `REDIS_USERNAME` (default empty) and
`REDIS_TLS` (default `false`). `RedisProperties` gains matching `username` and `tls` fields so the
Spring model service tracks the same configuration, wired in `application.yml` as
`${REDIS_USERNAME:}` and `${REDIS_TLS:false}`.

### The read-replica URIs

`routerFromEnv` and `routerFrom` build replica URIs by calling `standaloneUri` directly rather than
going through `uriFromEnv`/`uriFrom`. A change that updates only the primary path leaves every
replica connection unauthenticated and unencrypted while the primary looks correct — and since
reads route to replicas, that is most of the traffic. Both call sites must pass username, password,
and the TLS flag. The unit tests below assert this specifically, because nothing about the diff
makes the omission visible.

## The fail-closed guard

A blank password with no explicit opt-out refuses to start, mirroring
`GatewayAuthenticator.fromEnvironment`, which refuses to run without authentication unless
`GATEWAY_ALLOW_ANONYMOUS=true` says so out loud.

**The seam is the public entry points** — `fromEnv()`, `fromEnv(int)`, `routingFromEnv()`,
`routingFromEnv(int)`, `routerFromEnv()`, and `from(RedisProperties)` — not the package-private URI
builders. Unit tests that construct a URI to inspect it are then unaffected, and only code that
actually opens a connection is gated.

`REDIS_ALLOW_NO_AUTH=true` is the opt-out, set only where Redis is genuinely passwordless:

- `scripts/run-microservices-local.sh` and the `docker-compose*.yml` services, for local dev.
- Surefire's `<environmentVariables>` in `pom.xml`, so the existing suite needs no per-test edits.

`k8s/base` does **not** set it, because base now supplies a real password. That asymmetry is the
point: the flag marks environments that have consciously chosen no authentication, and production
is not one of them.

## Manifests

`recsys-secrets` gains a `redis-password` key. `REDIS_PASSWORD` is wired into the four workloads
that dial Redis plus the reconciliation CronJob, whose `OutboxReconciler` reads the online store.
The outbox relay is deliberately excluded: it touches MySQL and Kafka only.

The secret reference uses `optional: true`, matching `recsys-online-admin` and `recsys-splunk`. A
missing secret then produces a readable startup failure from the guard rather than a pod stuck
unschedulable with nothing in its logs.

In `k8s/base/redis-cluster.yaml`:

- **Primary and replica args** gain `--requirepass` and `--masterauth`, both from `REDIS_PASSWORD`.
  `masterauth` is what lets a replica authenticate *to* the primary; without it replication stops
  and the replicas serve indefinitely stale reads rather than failing.
- **Probes** stop passing credentials on the command line. All four `redis-cli` probes rely on
  `REDISCLI_AUTH` in the container environment instead — a `-a` flag puts the password in the
  process table and echoes it in probe failure output.
- **Sentinel** is the fiddly one. Its config is a ConfigMap template copied verbatim to `/data` by
  an initContainer, and a ConfigMap cannot hold a secret. The template gains
  `sentinel auth-pass mymaster __REDIS_PASSWORD__`, and the initContainer's `cp` becomes a `sed`
  substitution from the environment. Without this the sentinels cannot authenticate to the primary,
  never reach quorum, and never fail over — presenting as a Redis outage rather than as an auth
  error.

In `k8s/eks/redis-elasticache-patch.yaml` and its us-west-2 twin, `REDIS_TLS: "false"` is set
**explicitly**, with a comment stating that flipping it to `"true"` requires the ElastiCache cluster
to have encryption-in-transit enabled and an AUTH token provisioned — operator prerequisites
alongside the endpoint placeholder already documented there. An explicit `false` is what makes the
prerequisite discoverable; an absent key reads as "not applicable".

## Testing

Unit tests on URI construction, which need no Redis:

- `REDIS_TLS=true` produces a URI with `isSsl()` true; default produces false.
- `REDIS_USERNAME` set produces credentials carrying that username; unset falls back to the
  password-only path.
- **Replica URIs inherit username, password, and TLS** — the assertion that covers the omission the
  diff cannot show.

Guard tests: a blank password with no flag throws, and the message names `REDIS_PASSWORD` and
`REDIS_ALLOW_NO_AUTH` so the reader knows both the fix and the deliberate escape; with the flag set,
construction succeeds.

A manifest conformance test, in `src/test/java/com/recsys/infrastructure/k8s/`, following
`NetworkPolicyEgressManifestTest`: every workload that dials Redis has `REDIS_PASSWORD` wired from
the Secret, the Redis StatefulSets set `requirepass` and `masterauth`, and the sentinel template
carries `auth-pass`. This is the drift catcher — it is what stops a new overlay, or a "simplifying"
edit to the StatefulSet args, from quietly restoring an unauthenticated connection.

All of it is added to the `<includes>` of the `resilience` profile in `pom.xml`, which is what the
PR gate runs. None of it requires Docker or a live Redis.

## Documentation

- `20_AuthN_AuthZ.md` gains a data-tier authentication section. The document currently describes
  four ACL layers and states the data tier has none; that stops being true here.
- `docs/runbooks/redis-auth.md`: provisioning the secret, and the rotation procedure. Rotation under
  `requirepass` needs a coordinated restart of Redis and every client — the accepted cost of
  choosing `requirepass` over an ACL file, and worth writing down before it is discovered during an
  incident.
- The `20_AuthN_AuthZ` sharp-edges list loses nothing but gains a note that the ACL backlog item is
  now unblocked at the client layer.
