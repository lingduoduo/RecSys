# Redis per-service ACL users — design

Replace the single shared Redis credential with one ACL user per workload, scoped to the keys each
one actually writes.

## The gap

Every workload that touches Redis authenticates as the `default` user with the same
`REDIS_PASSWORD` from `recsys-secrets`: catalog serving, model serving, online serving, the API
gateway, and the reconciliation CronJob. `default` has the whole keyspace and every command. So any
one of those five, if compromised, can overwrite another's data or `FLUSHALL` the instance.

Transport authentication shipped in PR #274, and `LettuceClientFactory` already reads
`REDIS_USERNAME` (lines 120 and 188) — but **no manifest sets it**, so the support is inert.
Authorization is the half that was never done.

Found as the last open item of the 2026-08-05 ACL audit.

## What the keyspace actually looks like

The audit recorded "cleanly disjoint key ownership (`i2vEmb:`/`u2vEmb:` catalog, `sr:*`/`topk:*`
online, `svc:registry:*` gateway)". **That is wrong**, and the design that follows from it —
one user per service restricted to its own prefixes — would break recall on 8080 and 7010.

Derived from the call graph rather than the prefix names:

| Prefix | Written by | Read by |
|---|---|---|
| `i2vEmb:` | catalog serving — `EmbeddingService.setEmbedding` (`/setembedding`) and `RecSysServer.writeMissing` (startup seeding) | catalog, model |
| `u2vEmb:` | **nothing in `src/main/java`** — the Flink job writes it out-of-band | catalog, model, online |
| `topk:` | **nothing in `src/main/java`** — `ShardedTopKStore` exposes no write method at all | catalog, model, online |
| `sr:*` | online serving | online serving |
| `shard:topology` | online serving (bootstrap and reshard) | online serving |
| `svc:registry:<service>` | each service writes only its own key | gateway reads all |
| `lineage:event:*`, `user:*:recent_movies` | Flink | reconciliation CronJob, read-only |

Three of the four services read the same embedding and trending keyspace, so read isolation is not
available without moving those reads behind an API — a service-boundary redesign, not an ACL
change. **The boundary that the call graph does support is write access**, and that is what this
design enforces.

Two things fall out of the table that are worth stating on their own. The **gateway holds a
full-keyspace credential and needs almost nothing**: `LlmResponseCache` is an in-memory LRU keyed by
a SHA-256 of the request body, not a Redis structure, so the gateway's only Redis use is the service
registry. And **model serving never writes anything** — yet today it can `FLUSHALL`.

## The users

Five, one per workload:

| User | May write | May read |
|---|---|---|
| `catalog` | `i2vEmb:*`, `svc:registry:recsys-catalog-serving` | `u2vEmb:*`, `topk:*` |
| `model` | `svc:registry:recsys-model-serving` | `i2vEmb:*`, `u2vEmb:*`, `topk:*` |
| `online` | `sr:*`, `shard:topology`, `svc:registry:recsys-online-serving` | `u2vEmb:*`, `topk:*` |
| `gateway` | `svc:registry:recsys-api-gateway` | `svc:registry:*` |
| `reconciliation` | nothing | `lineage:event:*`, `user:*:recent_movies` |

All five are denied the dangerous command set — `FLUSHALL`, `FLUSHDB`, `CONFIG`, `DEBUG`, `SCRIPT`,
`KEYS` — via `-@dangerous`, then granted `+@read` and, where the table allows it, `+@write`.

`svc:registry:*` is a shared prefix with per-key ownership: each service renews only its own
advertised address, and the gateway MGETs the set. Redis key patterns express that directly, so the
write grant names the single key rather than the prefix.

## `default` stays exactly as it is

The Flink streaming job writes `u2vEmb:*`, `topk:*` and the feature keys, and **nothing in this repo
deploys it** — `online/flink/` is excluded from the Maven build and runs on its own cluster. It
authenticates as `default` today. Narrowing `default` would break the write path for embeddings and
trending with no error visible in this repo's tests or manifests.

That is exactly the silent-breakage shape this project has been caught by before, so `default` keeps
its current access and this document records why. Giving Flink its own user is a follow-up that
belongs wherever Flink is deployed, not here.

## Where it lives

The primary Redis in `k8s/base/redis-cluster.yaml` takes its whole configuration as **command-line
arguments**, including `--requirepass "$(REDIS_PASSWORD)"`. It mounts no config file at all — the
only ConfigMap in that manifest is the *sentinel* template. So there is no `redis.conf` to add
`user` directives to.

Passing them as arguments instead is not acceptable: an ACL user rule carries its password inline,
which would put five credentials in the process table. That manifest already refuses to do this once
— `REDISCLI_AUTH` exists precisely so a probe's `-a` flag does not leak the password into `ps` and
into probe failure output.

So the users go in an **`aclfile`, projected from a Secret** (it contains passwords, so a ConfigMap
is wrong), mounted read-only, with `--aclfile /etc/redis/users.acl` added to the argument list.
Read-only is sufficient because nothing calls `ACL SAVE`; the file is only ever read at startup.

**The ACL file must not define `user default`.** `requirepass` is Redis's shortcut for the default
user's password, and leaving default out of the file is what keeps the two mechanisms from
contending — which is also exactly what this design wants, since `default` is deliberately unchanged
for Flink's sake.

Each Deployment gets `REDIS_USERNAME` alongside its existing `REDIS_PASSWORD`, and each user's
password comes from a new key in the existing `recsys-secrets` Secret. `LettuceClientFactory`
consumes both already, so **no Java source changes**.

Each Deployment gets `REDIS_USERNAME` alongside its existing `REDIS_PASSWORD`, and each user's
password comes from a new key in the existing `recsys-secrets` Secret. `LettuceClientFactory`
consumes both already, so **no Java source changes**.

## Testing

`RedisAclManifestTest`, non-docker, in the `resilience` profile the PR gate runs. It derives each
service's key prefixes from the constants in `src/main/java` and asserts the ACL patterns in
`redis-cluster.yaml` cover exactly those — no more, no less. Adding a prefix to a service without
widening its ACL fails the build; widening an ACL beyond what the code uses fails it too.

The second direction is the one that matters. A test that only checks "the service can reach what it
needs" would pass an ACL granting `~*`, which is what exists today.

Each assertion must be shown to fail before it is trusted: narrow one user's pattern and the test
must name that user; widen one and it must name that too. This project has shipped four conformance
tests that asserted guarantees they did not provide, each caught only by an adversarial probe.

## What this does not do, and cannot

**Nothing is enforced anywhere today.** No EKS cluster exists in either region, and Docker is
unavailable on the development machines, so neither the manifests nor a running Redis can be
exercised. The deliverable is manifest correctness plus a merge-blocking conformance test.

**The EKS overlays do not use this Redis at all.** They point Redis at ElastiCache and scale the
in-cluster StatefulSet to zero. ElastiCache implements access control as RBAC users and user groups
managed through the AWS API — not `user` directives in a `redis.conf` — so none of these ACLs apply
there. Closing that gap means creating ElastiCache users out-of-band, the same shape as the
CloudFront and IRSA work, and it is out of scope here. The design covers the in-cluster Redis the
base manifests actually define, and says so rather than implying broader coverage.

**Read isolation is not attempted.** Three services read the same embedding keyspace by design; the
write split is the whole of the least-privilege claim.

**Two Redis behaviours here are asserted from documentation, not measured**, because no Redis can be
started on these machines: that `--aclfile` is accepted as a command-line argument like any other
directive, and that an ACL file which omits `user default` leaves `requirepass` governing default.
Both are load-bearing — if either is wrong, the StatefulSet fails to start. The plan records them as
the first thing to check the moment a Redis is reachable, and the conformance test cannot cover
either, since it reads manifests rather than running a server.

**Password rotation gets better, not worse.** `docs/runbooks/redis-auth.md` already records the
current cost — "`requirepass` holds exactly one password, so rotation is a coordinated restart
rather than an overlap window… moving the `default` user to an `aclfile` buys multi-password
overlap." A `user` directive takes several passwords at once, so rotating one service becomes: add
the new password to that user, roll that Deployment, drop the old one — no cross-service restart and
no Redis downtime. That applies per user, so it narrows the blast radius of a rotation as well as of
a compromise.

Five users do mean five secret keys where there was one. The runbook is updated with the per-user
procedure and with the fact that `default` — still the credential Flink uses — keeps the old
restart-based rotation. No automation is added.
