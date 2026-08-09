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

A first pass derived the following from the call graph. **Three of its rows are wrong**, and the
corrections are in "What was measured" below — it is kept here only because the shape of the
conclusion survives: reads are shared, writes are not.

| Prefix | Written by | Read by |
|---|---|---|
| `i2vEmb:` | catalog serving — `EmbeddingService.setEmbedding` (`/setembedding`) and `RecSysServer.writeMissing` (startup seeding) | catalog, model |
| `u2vEmb:` | ~~nothing~~ — **catalog also writes it**, via `RecSysServer.seedEmbeddings` | catalog, model, online |
| `topk:` | nothing directly — but `ShardedTopKStore` reads it through `EVAL`, which needs write permission anyway | catalog, model, online |
| `sr:*` | online serving | online serving |
| `shard:topology` | online serving (bootstrap and reshard) | online serving |
| `svc:registry:<service>` | each service writes only its own key | gateway reads all |
| `lineage:event:*` | Flink | reconciliation CronJob, read-only |
| `user:*:recent_movies` | Flink | ~~CronJob only~~ — **online and model read it too** |
| `rate:online:*` | online serving | online serving |
| `submit_token:*`, `login:*` | model serving | model serving |

Three of the four services read the same embedding and trending keyspace, so read isolation is not
available without moving those reads behind an API — a service-boundary redesign, not an ACL
change. **The boundary that the call graph does support is write access**, and that is what this
design enforces.

One thing falls out that is worth stating on its own: the **gateway holds a full-keyspace credential
and needs almost nothing**. `LlmResponseCache` is an in-memory LRU keyed by a SHA-256 of the request
body, not a Redis structure, so the gateway's only Redis use is the service registry — and it does
not script, so it is the one user that keeps a genuine read-only grant.

## What was measured

Before any of this was designed, two Redis behaviours were asserted from documentation. Both are
false, and a review that ran a real `redis-server` caught them. Everything below is measured.

**An ACL file that omits `user default` does not leave `requirepass` governing it.**
`ACLLoadFromFile` *rebuilds* the default user, and absent a `user default` line it comes back wide
open. With `--requirepass supersecret --aclfile <file>`:

```
ACL LIST → user default on nopass sanitize-payload ~* &* +@all
log       → # WARNING: Redis does not require authentication.
redis-cli ping (no auth) → PONG
```

That is not a subtle regression: it leaves the in-cluster Redis unauthenticated, undoing PR #274;
it breaks replication (`Unable to AUTH to MASTER … without any password configured for the default
user`, `master_link_status:down`); it breaks Flink; and both `redis-cli ping` probes stay green
throughout, so nothing reports it. **So `default` must be declared explicitly in the file**, and
`--requirepass` becomes redundant rather than authoritative.

**ACL files do not support comments.** Redis aborts startup on any non-blank line that does not
begin with `user`. All rationale therefore lives in `docs/runbooks/redis-auth.md` and beside the
`--aclfile` argument in `redis-cluster.yaml`, never in the file itself.

Three more, all measured:

- **`@scripting` is not covered by `@read`, `@write` or `@connection`.** Without `+@scripting` no
  user can `EVAL`, which breaks the trending read path on all three serving services
  (`ShardedTopKStore:198`), the sharded record write (`ShardedRecordStore:133`), topology publish
  (`ShardTopologyStore:71`), the online rate limiter (`RedisRateLimiter:192`) and the model
  service's submit-token consume (`SubmitTokenService:72`). `-@dangerous` does not strip it.
- **`%R~` is incompatible with `EVAL`.** Redis demands full read-write permission on every key
  passed to a script, even a read-only one carrying a `#!lua flags=no-writes` shebang. Since
  `ShardedTopKStore` passes `topk:<window>:value` and `:version` as KEYS, `topk:` cannot be
  read-only for anybody. It is granted `~` and the read-only split is forfeited there.
- **A read-only aclfile is fine.** Mounted `chmod 444`, the server starts normally; nothing calls
  `ACL SAVE`. This was the design's other open question, and the answer is yes.

Caveat on all of it: measured against `redis-server 8.6.2` locally, while the manifests pin
`redis:7-alpine` and no Docker daemon is available. The behaviours are longstanding — the aclfile
default rebuild since 6.0, `%R~`/`%W~` since 7.0 — but Redis 7 itself was not exercised.

## The users

Six, including `default`. The first table was a partial sweep and missed five prefixes; this one is
derived from the call graph and from the review's per-command testing.

| User | May write | May read only | Scripting |
|---|---|---|---|
| `default` | everything (`~* &* +@all`) | — | yes |
| `catalog` | `i2vEmb:*`, `u2vEmb:*`, `topk:*`, own registry key | — | yes |
| `model` | `submit_token:*`, `login:*`, `topk:*`, own registry key | `i2vEmb:*`, `u2vEmb:*`, `user:*:recent_movies` | yes |
| `online` | `sr:*`, `shard:topology`, `rate:online:*`, `topk:*`, own registry key | `u2vEmb:*`, `user:*:recent_movies` | yes |
| `gateway` | own registry key | `svc:registry:*` | no |
| `reconciliation` | nothing | `lineage:event:*`, `user:*:recent_movies` | no |

Every non-default user is `-@all +@read +@write +@connection -@dangerous`, plus `+@scripting` where
the table says so. `reconciliation` omits `+@write`. Order matters — `-@dangerous` last, so it
removes `FLUSHALL`, `FLUSHDB`, `CONFIG`, `DEBUG` and `KEYS` even though `@read`/`@write` granted
some of them. `SCAN`, `TTL`, `EXPIRE` and the `CLIENT` handshake commands survive, which
`RedisEmbeddingStore.scanIds` and Lettuce need.

Corrections the review forced into this table, each verified against source:

- **`catalog` writes `u2vEmb:*`.** `RecSysServer:96` calls `seedEmbeddings`, which at `:249` calls
  `writeMissing` on the user-embedding store. The first table claimed nothing in `src/main/java`
  writes it. That bites on a cold Redis — a fresh cluster, the DR region, or a partial eviction —
  which is exactly what `writeMissing` exists for.
- **`user:*:recent_movies` is read by `online` and `model`**, not only by the CronJob
  (`OnlineFeatureStore:93,98`, constructed at `OnlinePredictionServer:128` and
  `ModelRuntimeProvider:164`).
- **`rate:online:*`** belongs to online serving, **`submit_token:*` and `login:*`** to model
  serving. All three were absent.

## What the split still buys

Less than the first draft claimed, and worth stating plainly. `topk:` is read-write for three
services because of `EVAL`, and `default` remains omnipotent for Flink. What remains true, and was
verified per-command:

- model serving cannot write `i2vEmb:*` — it only scores
- no service can write another's `svc:registry:` key
- the reconciliation CronJob cannot write anything at all, and cannot read embeddings
- no application user can `FLUSHALL`, `FLUSHDB`, `CONFIG`, `DEBUG` or `KEYS`

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

---

## Status: NOT MERGEABLE — open findings from the 2026-08-09 final review

Everything below was **measured against a real `redis-server`**, not reasoned from documentation.
The branch boots and its gate is green at 613 tests; neither fact means what it appears to.

### C1 — no workload can authenticate as its own user

All five workloads take `REDIS_PASSWORD` from the single shared `recsys-secrets/redis-password`
key (`catalog-serving.yaml:89`, `model-serving.yaml:98`, `online-serving.yaml:122`,
`api-gateway.yaml:92`, `outbox-reconciliation-cronjob.yaml:61`), while the template gives each
service user a distinct `__<USER>_PASSWORD__` placeholder and the runbook says to generate distinct
passwords. Measured: `redis-cli --user catalog --pass <default's password>` → `WRONGPASS`.

So either every workload fails to connect, or all six placeholders render identically — which makes
the runbook's per-user rotation unimplementable. **Until this is fixed, all five still hold the
full-access credential and the ACL users are decorative.**

Fix: a per-user Secret key each (`redis-catalog-password`, …), with `redis-cluster.yaml`'s own
`REDIS_PASSWORD`/`REDISCLI_AUTH` staying on `redis-password` since those are the `default`
credential.

### C2 — accesses the code performs that the ACLs deny

| Access | Call site | Users needing it |
|---|---|---|
| `ZREVRANGE global:item_popularity` | `RecSysServer:111`, `OnlinePredictionServer:130`, `ModelRuntimeProvider:162` | catalog, model, online |
| `SET bias:item:<id>` | `OnlineLearner:116` | online |
| `SET/GET recsys:replica-lag-probe:*` | `OnlinePredictionServer:172` | online |
| `SISMEMBER lineage:event:*` | `OnlinePredictionServer:215` | online |
| `GET <key>:updated_at` | `RedisFeatureVersionSampler:31-35` | online |
| `TTL` on any scanned key | `RedisPersistentKeyProbe` | online |
| `INFO` (stripped by `-@dangerous`, needs an explicit `+info`) | `RedisCacheStatsProbe:55` | online |

Popularity and ColdStart recall return **empty silently** on all three serving services —
`GlobalPopularityStore` swallows the exception — and three alerts
(`prometheus-rules.yaml:102`, `:113`, `:187`) would fire permanently.

### C3 — the keyspace sweep has been wrong three times, and the method is why

This document has listed 10 prefixes. **~30 classes in `src/main/java` consume a `RedisExecutor`.**
Every pass so far enumerated *stores* and each missed a different set. Any future attempt must
enumerate **every `RedisExecutor` consumer** and every key literal it builds, then map those to the
workloads that construct it — and record in the table that it was derived that way.

### C4 — `RedisAclManifestTest` proves little

It stays 5/5 green under five mutations that each break a real server: `~` → `%W~` (the derived
write-set cannot tell them apart); `+@scripting` deleted from `online` (nothing asserts it); a
`+@dangerous` appended after `-@dangerous` (so `FLUSHALL` returns `OK`); a password rule replaced
with `nopass` (the placeholder check only inspects rules starting with `>`); and `-@all` moved after
the grants (leaving the user able to do nothing). Fix: assert the **exact ordered rule-token list**
per user.

### Important

- `RedisAclManifestTest`'s own javadoc still claims nothing writes `topk:`/`u2vEmb:`, contradicted
  six lines below it and by `RecSysServer:249`.
- The `redis-users.acl` Secret key is a hard, undocumented startup dependency — no `optional`, and
  `redis-server` aborts on a missing `--aclfile` regardless. Applying `k8s/base` before the key
  exists leaves both Redis pods in `CreateContainerConfigError`.
- The aclfile **silently overrides** `--requirepass`: `CONFIG GET requirepass` still reports the CLI
  value while only the aclfile password authenticates. The runbook says they "must agree" but not
  that the file wins.
- `model`'s `%R~i2vEmb:*` is latent unless `recsys.model.item-embeddings-source=redis`.

### What is genuinely fixed and worth keeping

The template boots; `default` carries a real password rather than `nopass` and unauthenticated
`PING` is refused; `+@scripting` plus `~topk:*` make the trending `EVAL` work for all three serving
users; the write split holds where it is granted (catalog cannot write `sr:`, model cannot write
`i2vEmb:`, the gateway cannot write another service's registry key, reconciliation cannot write at
all, and `FLUSHALL` is denied to all five); and `~sr:*` covers both shard key formats.

All measurement was on `redis-server 8.6.2` while the manifests pin `redis:7-alpine`; no Docker
daemon was available to exercise Redis 7 itself.
