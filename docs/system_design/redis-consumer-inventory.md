# Redis consumer inventory

Every place in this repository that issues a Redis command, what it issues, against which keys,
on behalf of which workload, and whether a denial would be visible.

This exists to give the per-service Redis ACL work (`k8s/base/redis-users.acl.template`) a
keyspace list that is derived rather than remembered. It is a **census, not a design**: nothing
here proposes a rule change. The ACL template, the manifests and the tests are untouched by this
document.

---

## 0. Derived by consumer, not by store — and why that matters

The keyspace sweep behind the ACL template has been wrong three times. Each attempt enumerated
Redis **stores** — `RedisEmbeddingStore`, `ShardedTopKStore`, `ShardedRecordStore`, … — and each
missed a different set of accesses, because a store is the wrong unit:

- **Some consumers build keys without being a store.** `RedisLineageReader` (22 lines),
  `RedisReplicaLagProbe`, `RedisFeatureVersionSampler` and `RedisCacheStatsProbe` are readers and
  probes. They own key literals (`lineage:event:*`, `recsys:replica-lag-probe:*`, `*:updated_at`)
  and one of them needs a command — `INFO` — that no store issues.
- **Some consumers touch several stores, and the union is what the ACL must cover.**
  `OnlinePredictionServer` alone constructs eleven Redis-touching objects spanning six namespaces.
  Enumerating stores in isolation never produces that union.
- **Some consumers own the key prefix, not the store.** `OnlineLearner.flushToRedis` takes the
  prefix as a *parameter*; the literal `"bias:item"` lives in `OnlinePredictionServer:164`, not in
  `OnlineLearner`. Reading `OnlineLearner` alone shows no key at all. This is exactly how
  `SET bias:item:<id>` was missed.
- **Some stores are constructed by nobody.** `RedisTopKStore`, `RedisDistributedLock`,
  `RedisMutex` and `WatchdogLock` are exercised only by unit tests. A store-first sweep grants
  `dlock:`, `mutex:`, `wdlock:` and a second `topk:` writer that no workload ever needs.
- **Package name is not ownership.** `application/consistency/RedisLineageReader` is constructed
  by *online serving* and by the *reconciliation CronJob*; `config/ServiceRegistryConfig` is
  Spring-only, so it belongs to model serving. Every attribution below was traced through
  `new X(...)` chains and `@Bean`/`@Service` registration, never inferred from the package.

The most expensive consequence of the store-first method is documented in the branch's own review:
all three serving services read `global:item_popularity` through `GlobalPopularityStore`, which
**swallows the exception** (`GlobalPopularityStore.java:40-44`). An ACL denial there makes
Popularity and ColdStart recall return empty with no error, no log line and no metric — the
degradation is invisible until someone notices the recall mix changed.

So the unit here is the **consumer**: any class that names a `RedisExecutor` and reaches a
`RedisCommands` call, plus anything that reaches Redis by another route.

---

## 1. Scope and how the list was closed

**In scope.** All 35 classes in `src/main/java` that reference
`com.recsys.infrastructure.redis.RedisExecutor`, plus a sweep for Redis access that never names
that interface.

**The sweep for non-`RedisExecutor` access** covered `io.lettuce` client construction
(`RedisClient.create`, `RedisURI`, `StatefulRedisConnection`, `RedisCommands`,
`RedisAsyncCommands`), Jedis (`redis.clients.jedis`, `JedisPool`), Spring Data Redis
(`RedisTemplate`, `LettuceConnectionFactory`, `spring.redis` / `spring.data.redis`), Redisson,
Spring Cache over Redis, `redis-cli` in scripts, and Flink/Spark Redis sinks. Results:

- There is **no** `spring-boot-starter-data-redis` dependency and no `spring.redis` /
  `spring.data.redis` configuration anywhere, so no auto-configured `RedisTemplate` or
  `LettuceConnectionFactory` can exist. `config/RedisProperties.java` is a pure
  `@ConfigurationProperties` POJO — it imports nothing from `io.lettuce` and issues no commands.
- There is **no** Jedis, Redisson, or `@EnableCaching`-over-Redis in this repository. All
  in-process caching is Caffeine.
- Two Java classes construct a Lettuce client directly, and **both are excluded from the Maven
  compile** (`pom.xml:67-70`) — see §5.
- Two shell scripts drive `redis-cli` against real data — see §5.

**The supplied list of 35 was complete for the compiled main source set.** Nothing inside
`src/main/java` reaches Redis without naming `RedisExecutor` except the two Maven-excluded classes.

**Counts.** 35 referencing classes = 6 executor plumbing (§2) + **29 consumers** (§3–§4). Of the
29, 25 are constructed by at least one workload; 4 are test-only.

**Workload abbreviations.**

| Tag | Workload | Entry point |
|---|---|---|
| **CAT** | Catalog serving, 6010 | `api/serving/RecSysServer` |
| **ONL** | Online serving, 7010 | `api/online/OnlinePredictionServer` |
| **MODEL** | Model serving, 8080 | `api/rest/ModelApplication` + `config/*` beans + `application/model/ModelRuntimeProvider` |
| **GW** | API gateway, 8010 | `api/gateway/MicroserviceGatewayServer` |
| **RECON** | Reconciliation CronJob | `application/reconciliation/ReconciliationCommand` |

---

## 2. Executor plumbing — confirmed to build no keys

Six of the 35 are the executor layer. This was **verified by reading each one**, not assumed:

| Class | What it does | Keys built |
|---|---|---|
| `infrastructure/redis/RedisExecutor` | Interface. Six methods, all taking a `Function<RedisCommands,T>` or `Consumer<StatefulRedisConnection>` from the caller. | none |
| `infrastructure/redis/LettuceRedisExecutor` | Shared connection + commons-pool2 pool; `execute`/`executePrimaryRead`/`executePipelined` apply the caller's lambda (`:72`, `:95`, `:138`). | none |
| `infrastructure/redis/LazyRedisExecutor` | Double-checked-locking decorator; every method delegates (`:30-55`). | none |
| `infrastructure/redis/RoutingRedisExecutor` | Splits reads from writes across the router (`:32-63`). | none |
| `infrastructure/redis/RedisReadReplicaRouter` | Selects primary vs AZ-local replica executor (`:53`, `:67`, `:80`). Holds no connection. | none |
| `infrastructure/redis/LettuceClientFactory` | Builds `RedisClient`/`RedisURI`, applies TLS, and applies `AUTH` — with a username for a Redis 6 ACL login, without one for the legacy default user (`:214-215`). Refuses an unauthenticated connection unless `REDIS_ALLOW_NO_AUTH=true` (`:51-63`). | none — but it is the **only** place credentials are attached, so every consumer below inherits one identity per process. |

Consequence for ACL scoping: a workload authenticates **once per `RedisExecutor`**, and every
consumer sharing that executor shares the identity. `LoginTokenService` and `SubmitTokenService`
each build their *own* executor via `LettuceClientFactory.fromEnv()` (`:28`, `:34`), and
`ModelRuntimeProvider` builds up to two more (`:159`, `:253`) — all from the same env vars, so
they are the same identity, just extra connections.

---

## 3. Per-consumer inventory

Read/write patterns are Redis globs. Where a prefix is a constructor argument, the **actual
literal passed at the traced construction site** is used, and the site is named.

Trending windows are `last_hour`, `last_day`, `last_month` (`Channels.java:164-166`,
`ColdStartChannel.java:18-19`, `OnlineRecommendationService.java:29`).

| Consumer — call sites | Workload(s) | Commands | Keys read | Keys written | Lua | Failure silent? |
|---|---|---|---|---|---|---|
| **`infrastructure/redis/GlobalPopularityStore`**<br>`:51` ZREVRANGE (primary), `:57` ZREVRANGE (replica-routed) | **CAT** (`RecSysServer:111`), **ONL** (`OnlinePredictionServer:130`), **MODEL** (`ModelRuntimeProvider:163`) | `ZREVRANGE` | `global:item_popularity` | — | no | **YES** — `:40-44` catches `RuntimeException` and returns `List.of()`. No log, no metric. Popularity + ColdStart recall silently degrade. |
| **`infrastructure/redis/RedisEmbeddingStore`**<br>`:80`,`:87` GET · `:92`,`:103` SET · `:121`,`:123` SET (pipelined) · `:155`,`:186`,`:212` MGET · `:208`,`:230`,`:253`,`:268` SCAN | **CAT** `i2vEmb` (`RecSysServer:92`) and `u2vEmb` (`:93`); **ONL** `u2vEmb` (`OnlinePredictionServer:119`); **MODEL** `u2vEmb` (`ModelRuntimeProvider:161`) and `i2vEmb` (`:255`, only when `recsys.model.item-embeddings-source=redis`) | `GET`, `SET` (plain / `PX`), `MGET`, `SCAN MATCH` | `i2vEmb:*`, `u2vEmb:*` | `i2vEmb:*`, `u2vEmb:*` — **CAT only**: `writeMissing` at `RecSysServer:245,249` and `EmbeddingService:57,92`. ONL and MODEL never write. | no | no — `getEmbedding` propagates; the caches above it decide |
| **`infrastructure/redis/ShardedTopKStore`**<br>`:162` EVAL + `:164` ZREVRANGE (primary) · `:172-177` EVAL + ZREVRANGE (read) · `:198` EVAL | **CAT** (`RecSysServer:94`), **ONL** (`OnlinePredictionServer:127`), **MODEL** (`ModelRuntimeProvider:162`) — prefix `"topk:"` at all three | `EVAL`, `ZREVRANGE` | `topk:{last_hour}:value`, `topk:{last_day}:value`, `topk:{last_month}:value`, `topk:{<window>}:version`, legacy `topk:last_hour` / `topk:last_day` / `topk:last_month` | — (never writes) | **YES** — `READ_CANONICAL_SNAPSHOT` (`:46-51`) passes `topk:{<w>}:value` **and** `topk:{<w>}:version` as `KEYS`. Both need full read-write ACL permission even though the script only reads. | partial — `:139-142` serves a stale JVM snapshot inside the 60 s stale window; outside it, rethrows |
| **`infrastructure/store/OnlineFeatureStore`**<br>`:99` GET (primary) · `:200` GET · `:209` MGET | **ONL** (`OnlinePredictionServer:128`), **MODEL** (`ModelRuntimeProvider:164`) | `GET`, `MGET` | `user:*:recent_movies` — the only literal built (`:93`, `:98`). The arbitrary-key `getFeature`/`getFeatures` entry points have **no caller in `src/main/java`**. | — | no | partial — `getFeatures` `:152-157` logs and serves stale; `getCachedOrLoad` rethrows when no stale value exists |
| **`infrastructure/registry/ServiceRegistryStore`**<br>`:32` SET PX · `:37` DEL · `:45` MGET | **CAT** (`RecSysServer:83`), **ONL** (`OnlinePredictionServer:106`), **GW** (`MicroserviceGatewayServer:106`), **MODEL** (`ServiceRegistryConfig:23`) — prefix `"svc:registry:"` at all four | `SET PX`, `DEL`, `MGET` | GW only: `svc:registry:recsys-catalog-serving`, `svc:registry:recsys-model-serving`, `svc:registry:recsys-online-serving` (names from `MicroserviceRoute:25-28`, MGET at `ServiceRegistryProvider:56`) | CAT/ONL/MODEL only: `svc:registry:<$SERVICE_REGISTRY_SERVICE_NAME>` — **an env value with no default and set in no manifest**. **GW never writes** — it has no `ServiceRegistrar`. | no | **YES** — `ServiceRegistrar.heartbeat` `:69-71` and `close` `:84-86` catch and log at WARN |
| **`ratelimit/RedisRateLimiter`**<br>`:192` EVAL | **ONL** (`OnlinePredictionServer:192`), prefix `"rate:online:"` (`:92`) | `EVAL` (script runs `GET`, `INCR`, `PEXPIRE`) | `rate:online:*` | `rate:online:*` | **YES** — declares `KEYS[1] = rate:online:<bucket>`, then the script builds `KEYS[1]..':'..windowId` internally (`:49-50`), so the keys actually touched are `rate:online:<bucket>:<windowId>` | **YES** — `:206-211` catches, logs WARN, opens a circuit and fails open to a local bucket. Traffic keeps flowing with no cluster-wide limit. |
| **`infrastructure/redis/sharding/ShardedRecordStore`**<br>`:133` EVAL · `:190` ZRANGEBYSCORE · `:287` XREAD · `:314` HGETALL (pipelined) | **ONL** (`OnlinePredictionServer:203`), prefix `"sr:"` | `EVAL` (script runs `INCR`, `ZADD`, `HSET`, `EXPIRE`, `XADD`), `ZRANGEBYSCORE`, `XREAD`, `HGETALL` | `sr:*` — `sr:dev:<shard>:<dev>`, `sr:stream:<shard>`, `sr:rec:<shard>:<seq>`; tagged format `sr:dev:{<shard>}:<dev>`; generation ≥2 `sr:g<v>:…` | `sr:*` — same set plus `sr:seq:<shard>` | **YES** — `WRITE_RECORD_LUA` (`:53-74`) declares `sr:seq:…`, `sr:dev:…`, `sr:stream:…` as `KEYS` and **constructs `sr:rec:…` inside the script** (`:65`), so the record key is touched without being declared | no |
| **`infrastructure/redis/sharding/SequenceGenerator`**<br>`:38` INCR · `:71` GET · `:74` SET · `:85`,`:96` SCAN · `:88` ZREVRANGEBYSCORE | **ONL** (`OnlinePredictionServer:202`), prefix `"sr:"` | `INCR`, `GET`, `SET`, `SCAN MATCH`, `ZREVRANGEBYSCORE WITHSCORES` | `sr:seq:*`, `sr:dev:*` (scan pattern from `ShardKeys.devScanPattern`) | `sr:seq:*` | no | **YES** for the startup repair — `OnlinePredictionServer:360-362` catches and logs WARN on a daemon thread |
| **`infrastructure/redis/sharding/ShardTopologyStore`**<br>`:55` GET · `:65` SET NX · `:71` EVAL | **ONL** (`OnlinePredictionServer:197`), key literal `"shard:topology"` | `GET`, `SET NX`, `EVAL` | `shard:topology` | `shard:topology` | **YES** — `PUBLISH_LUA` (`:23-44`) declares `shard:topology` and does `GET` + `SET` on it | **YES** — `ShardTopologyProvider:78-79` (bootstrap) and `:107-108` (30 s refresh) catch and keep the last-good snapshot |
| **`application/consistency/RedisLineageReader`**<br>`:20` SISMEMBER (timed primary read) | **ONL** (`OnlinePredictionServer:215`), **RECON** (`ReconciliationCommand:71`) | `SISMEMBER` | `lineage:event:*` — key is `lineage:event:<eventId>`; `user:<id>:recent_movies` is the **set member**, not a key | — | no | no — propagates to `ConsistencyWaiter` |
| **`application/online/OnlineLearner`**<br>`:116` SET (pipelined) · `:131`,`:149` SCAN + `:135` MGET | **ONL** (`OnlinePredictionServer:129`) | `SET` (pipelined), and `SCAN`/`MGET` in `loadFromRedis` | `bias:item:*` — **only via `loadFromRedis`, which has no caller in `src/main/java`**; the namespace is write-only in production | `bias:item:*` — prefix `"bias:item"` supplied by `OnlinePredictionServer:164`, **not by this class** | no | not itself — but its only caller swallows (next row) |
| **`application/online/LearnerFlushScheduler`**<br>`:47` → `OnlineLearner.flushToRedis(exec, "bias:item")` | **ONL** (`OnlinePredictionServer:164`) | (delegated `SET`) | — | `bias:item:*` | no | **YES** — `:50-53` catches, increments an error counter, logs WARN. Runs every 30 s on a daemon thread. |
| **`infrastructure/redis/RedisReplicaLagProbe`**<br>`:47` SET EX · `:48` GET (replica only) | **ONL** (`OnlinePredictionServer:172`) | `SET EX`, `GET` | `recsys:replica-lag-probe:<uuid>` (per-process, `:34`) | `recsys:replica-lag-probe:*` | no | **YES** — `:58-60` catches and reports `unavailable`, which is indistinguishable from "no replica configured" |
| **`infrastructure/redis/RedisCacheStatsProbe`**<br>`:55` INFO | **ONL** (`OnlinePredictionServer:179`) | **`INFO`** (no key) | — | — | no | **YES** — `:65-67` catches and returns `CacheStats.unavailable()` |
| **`infrastructure/redis/RedisPersistentKeyProbe`**<br>`:95` SCAN · `:104` TTL | **ONL** (`OnlinePredictionServer:182`) | `SCAN` (no `MATCH` — whole keyspace), `TTL` | **any key the scan returns**, minus the declared-durable prefixes `shard:topology`, `i2vEmb:`, `u2vEmb:`, `sr:`, `bias:item:` (`:57`). Effectively `*`. | — | no | **YES** — `:115-118` catches, logs WARN, returns `unavailable` |
| **`infrastructure/redis/RedisFeatureVersionSampler`**<br>`:31` SCAN MATCH · `:35` GET | **ONL** (`OnlinePredictionServer:186`) | `SCAN MATCH`, `GET` | `*:updated_at` | — | no | **YES in production** — `sample()` rethrows, but the scheduler at `:54` swallows every `RuntimeException`. A denial shows only as a frozen `redis_feature_version_age_seconds`. |
| **`application/auth/LoginTokenService`**<br>`:38` SET NX EX · `:54` GET · `:64` DEL | **MODEL** (`@Service`, `:19`) | `SET NX EX`, `GET`, `DEL` | `login:*` | `login:*` (TTL 86 400 s) | no | no — `:40` and `:57` throw |
| **`application/auth/SubmitTokenService`**<br>`:56` SET NX EX · `:72` EVAL | **MODEL** (`@Service`, `:17`) | `SET NX EX`, `EVAL` (script runs `GET`, `DEL`) | `submit_token:*` (prefix `recsys.submit-token.key-prefix`, default `submit_token:` — `SubmitTokenProperties:18,41`) | `submit_token:*` | **YES** — `CONSUME_SCRIPT` (`:22-27`) declares `submit_token:<token>` | no — throws `SubmitTokenException`. **Dormant by default**: `RECSYS_SUBMIT_TOKEN_ENABLED` defaults to `false` (`application.yml:128`). |
| **`application/model/ModelRuntimeProvider`**<br>`:159`, `:253` build executors; constructs the stores at `:161-164`, `:255` | **MODEL** (`@Service`, `:46`; recall infra built eagerly via `afterSingletonsInstantiated` → `warmUp`) | none directly | — | — | no | n/a — but `ModelArtifactService:88` drives `RedisEmbeddingStore.loadAll()` (SCAN + MGET over `i2vEmb:*`) when the Redis item-embedding source is selected |
| **`config/RedisConfig`**<br>`:21`, `:30` | **MODEL** (`@Configuration`, `:9`) | none | — | — | no | n/a — builds the `RedisExecutor` and `RedisReadReplicaRouter` beans only |
| **`config/ServiceRegistryConfig`**<br>`:23` constructs `ServiceRegistryStore("svc:registry:")` | **MODEL** (`@Configuration`, `:17`) | none directly | — | (via `ServiceRegistryStore`) | no | n/a |
| **`api/serving/RecSysServer`**<br>`:79` executor; `:83`, `:92-94`, `:111` construct consumers; `:245`,`:249` seed writes | **CAT** | none directly | — | — | no | n/a — owns the literals `i2vEmb`, `u2vEmb`, `topk:` |
| **`api/online/OnlinePredictionServer`**<br>`:104` executor; `:106`,`:119`,`:127-130`,`:164`,`:172`,`:179`,`:182`,`:186`,`:192`,`:197`,`:202-203`,`:215` construct consumers | **ONL** | none directly | — | — | no | n/a — owns the literals `u2vEmb`, `topk:`, `bias:item`, `shard:topology`, `sr:` |
| **`api/gateway/MicroserviceGatewayServer`**<br>`:104` executor, `:106` store — **only when `SERVICE_REGISTRY_ENABLED=true`** (`:99-103`) | **GW** | none directly | — | — | no | n/a — with the flag off the gateway opens no Redis connection at all |
| **`application/reconciliation/ReconciliationCommand`**<br>`:67` executor, `:71` `RedisLineageReader` | **RECON** | none directly | — | — | no | n/a |

### Consumers no workload constructs

Verified by grepping `new <Class>(` across `src/main/java`: zero hits for each. They are exercised
only from `src/test/java`. **No ACL user needs their key spaces.**

| Consumer | Call sites | Key space it would use | Lua |
|---|---|---|---|
| `infrastructure/redis/RedisTopKStore` | `:88-90`, `:99-102`, `:121` | `topk:*` (a second, unused `topk:` reader) | yes — same canonical-snapshot script as `ShardedTopKStore` |
| `infrastructure/lock/RedisDistributedLock` | `:88` SETNX, `:90` EXPIRE, `:109` EVAL, `:127` SET NX PX, `:143` EVAL | `dlock:*` | yes — `ACQUIRE_LUA`, `RELEASE_LUA` |
| `infrastructure/lock/RedisMutex` | `:77` SET NX EX, `:88` EVAL | `mutex:*` | yes — `RELEASE_SCRIPT` |
| `infrastructure/lock/WatchdogLock` | `:126` SET NX EX, `:148` EVAL, `:191` EVAL | `wdlock:*` | yes — `RENEW_SCRIPT`, `RELEASE_SCRIPT` |

---

## 4. Roll-up per ACL user

Union of everything §3 attributes to each workload. **"Script keys" is the column that constrains
the rules**: Redis requires full read-write permission on every key passed to a script, even a
read-only one carrying a `#!lua flags=no-writes` shebang, so a script key can never be granted
`%R~`.

### `catalog` — `RecSysServer`, 6010

| | |
|---|---|
| **Reads** | `i2vEmb:*`, `u2vEmb:*`, `global:item_popularity`, `topk:{<window>}:value`, `topk:{<window>}:version`, `topk:<window>` |
| **Writes** | `i2vEmb:*`, `u2vEmb:*` (startup seed + `/setembedding`), `svc:registry:<$SERVICE_REGISTRY_SERVICE_NAME>` (registry off in every manifest) |
| **Script keys (need full RW)** | `topk:{<window>}:value`, `topk:{<window>}:version` |
| **Non-keyspace commands** | `SCAN` (embedding `loadAll`/`scanIds`), `EVAL`, plus the connection handshake |

### `model` — `ModelApplication`, 8080

| | |
|---|---|
| **Reads** | `u2vEmb:*`, `i2vEmb:*` (only when `recsys.model.item-embeddings-source=redis`), `user:*:recent_movies`, `global:item_popularity`, `topk:{<window>}:value`/`:version`, `topk:<window>`, `login:*`, `submit_token:*` |
| **Writes** | `login:*`, `submit_token:*`, `svc:registry:<$SERVICE_REGISTRY_SERVICE_NAME>` |
| **Script keys (need full RW)** | `topk:{<window>}:value`, `topk:{<window>}:version`, `submit_token:*` |
| **Non-keyspace commands** | `SCAN` (`loadAll` over `i2vEmb:*`), `EVAL`, connection handshake |

### `online` — `OnlinePredictionServer`, 7010

| | |
|---|---|
| **Reads** | `sr:*`, `shard:topology`, `rate:online:*`, `topk:{<window>}:value`/`:version`, `topk:<window>`, `u2vEmb:*`, `user:*:recent_movies`, `global:item_popularity`, `lineage:event:*`, `*:updated_at`, `recsys:replica-lag-probe:*`, **and `TTL` on any key the persistent-key probe scans** |
| **Writes** | `sr:*`, `shard:topology`, `rate:online:*`, `bias:item:*`, `recsys:replica-lag-probe:*`, `svc:registry:<$SERVICE_REGISTRY_SERVICE_NAME>` |
| **Script keys (need full RW)** | `sr:seq:*`, `sr:dev:*`, `sr:stream:*`, **`sr:rec:*` (built inside the script, never declared)**, `rate:online:*` (**the declared key is `rate:online:<bucket>`; the script touches `rate:online:<bucket>:<windowId>`**), `shard:topology`, `topk:{<window>}:value`/`:version` |
| **Non-keyspace commands** | **`INFO`**, `SCAN` (unfiltered keyspace walk, `sr:dev:*`, `*:updated_at`), `TTL`, `EXPIRE` (inside the record script), `EVAL`, `XREAD`, connection handshake |

### `gateway` — `MicroserviceGatewayServer`, 8010

| | |
|---|---|
| **Reads** | `svc:registry:recsys-catalog-serving`, `svc:registry:recsys-model-serving`, `svc:registry:recsys-online-serving` (one `MGET`) |
| **Writes** | **none** |
| **Script keys** | none — the gateway runs no script |
| **Non-keyspace commands** | connection handshake only |
| **Note** | The gateway opens no Redis connection unless `SERVICE_REGISTRY_ENABLED=true` (`:99-103`). No manifest sets it. |

### `reconciliation` — `ReconciliationCommand` CronJob

| | |
|---|---|
| **Reads** | `lineage:event:*` (`SISMEMBER`) |
| **Writes** | **none** |
| **Script keys** | none |
| **Non-keyspace commands** | connection handshake only |
| **Note** | `user:<id>:recent_movies` is the **member** passed to `SISMEMBER`, not a key. ACL key patterns do not apply to set members. |

---

## 5. Redis access outside the compiled main source set

These are real writers against the same keyspace and matter for ACL scoping, but none of them
authenticates through `LettuceClientFactory`.

| Where | How it connects | Commands | Keys |
|---|---|---|---|
| `online/flink/OnlineFeatureStreamingJob` (**excluded from the Maven compile**, `pom.xml:67-70`) — `AbstractRedisSink:949-961` | `RedisClient.create(RedisURI.create(host, port))`, one unpooled connection per subtask. **No username, no password, ever** | `EVAL` only — scripts run `GET`, `SETEX`, `RPUSH`, `LTRIM`, `EXPIRE`, `SADD`, `ZADD`, `SET`, `DEL` | writes `user:*:recent_movies`, `u2vEmb:*`, `feature:user:*:session:*`, `movie:*:*`, `*:updated_at`, `*:last_event`, `*:event_history`, `lineage:event:*`, `topk:{<window>}:value`, `topk:{<window>}:version`, `feature:{<window>}:hot_movies`, `feature:{<window>}:trend`, `lineage:{<window>}:event:*` |
| `training/rulebased/ItemEmbeddingJob:163-183` (**excluded from the Maven compile**) | `RedisClient.create(RedisURI.create(host, port))` per Spark partition. **No auth** | `SET [EX]` (pipelined) | writes `i2vEmb:*` (`config.redisKeyPrefix()`) |
| `streaming/online-serving/scripts/load_online_features.sh:20-29` | `redis-cli`, **no `-a` / `--user`** | `SET … EX`, `DEL`, `ZADD`, `EXPIRE` | `user:*`, `movie:*`, `topk:*` |
| `scripts/simulate-elasticache-eviction.sh:66-121` | its own local `redis-server` | `SET`, `EVAL`, `EXISTS`, `INFO`, `SETEX` | `shard:topology`, `i2vEmb:*`, `filler:*`, `durablefill:*` — sandbox only |

Health probes in `k8s/base/redis-cluster.yaml`, `docker-compose.streaming.yml` and
`streaming/online-serving/docker-compose.yml` run `redis-cli ping` only; `redis-cluster.yaml:130`
notes they rely on `REDISCLI_AUTH`.

---

## 6. What the current template denies that the code performs

`k8s/base/redis-users.acl.template` as of this branch. Every non-default user is
`-@all +@read +@write +@connection -@dangerous`, plus `+@scripting` where granted;
`reconciliation` omits `+@write`.

### 6a. Denied but performed — 7 accesses

| # | Access | Call site | Users that need it | Visible on denial? |
|---|---|---|---|---|
| 1 | `ZREVRANGE global:item_popularity` | `GlobalPopularityStore:51,57` via `RecSysServer:111`, `OnlinePredictionServer:130`, `ModelRuntimeProvider:163` | catalog, model, online | **No.** `:40-44` swallows. Popularity + ColdStart recall return empty on **all three** serving services. |
| 2 | `SET bias:item:<id>` | `OnlineLearner:116`, prefix from `OnlinePredictionServer:164` | online | **No.** `LearnerFlushScheduler:50-53` catches and logs WARN. Learned biases stop persisting; restarts lose them. |
| 3 | `SET`/`GET recsys:replica-lag-probe:<uuid>` | `RedisReplicaLagProbe:47,48` | online | **No.** `:58-60` returns `unavailable`, identical to "no replica configured". |
| 4 | `SISMEMBER lineage:event:<id>` | `RedisLineageReader:20` via `OnlinePredictionServer:215` | online | Yes — propagates to `ConsistencyWaiter`. (RECON is granted this and is unaffected.) |
| 5 | `GET <key>:updated_at` | `RedisFeatureVersionSampler:35` | online | **No.** `:54` swallows. `redis_feature_version_age_seconds` freezes at its last good value. |
| 6 | `TTL <any scanned key>` | `RedisPersistentKeyProbe:104` | online | **No.** `:115-118` returns `unavailable`. |
| 7 | **`INFO`** | `RedisCacheStatsProbe:55` | online | **No.** `:65-67` returns `unavailable`. `INFO` carries `@dangerous`, so `-@dangerous` strips it; it needs an explicit `+info`. |

**Six of the seven are silent.** That is the load-bearing fact: the branch's ACL rollout would
look successful — services boot, health checks pass, the test gate is green — while recall quality
degrades on three services and four observability signals freeze.

`SCAN` itself survives `-@dangerous` and takes no key argument, so the scans in items 5–7 succeed;
only the follow-up per-key command is denied. That is what makes these failures partial and quiet
rather than loud.

### 6b. Granted but unused

| Grant | User | Why nothing uses it |
|---|---|---|
| `~svc:registry:recsys-api-gateway` (read **and write**) | gateway | The gateway has no `ServiceRegistrar` — it only calls `store.lookup` (`ServiceRegistryProvider:56`). It never registers itself, so it never writes any registry key. |
| `+@write` | gateway | Follows from the row above: `MGET` is the gateway's only Redis command. `+@read +@connection` would cover it. |
| `%R~user:*:recent_movies` | reconciliation | `RedisLineageReader:19-20` passes that string as the **member** of `SISMEMBER`, not as a key. ACL key patterns never see it. |

### 6c. Granted but dormant — correct to keep, currently unexercised

Not "unused" in the §6b sense; these are live code paths behind a flag that is off.

| Grant | User | Gate |
|---|---|---|
| `~svc:registry:recsys-catalog-serving` / `-model-serving` / `-online-serving` | catalog, model, online | Registration needs `SERVICE_REGISTRY_ENABLED=true` **plus** `SERVICE_REGISTRY_SERVICE_NAME` and `SERVICE_REGISTRY_ADVERTISE_URL` (`ServiceRegistrar:39-47`). **No manifest under `k8s/` sets any of the three**, so nothing registers today — and the three literal names in the template are assumed, not derived from configuration. The name written is whatever `SERVICE_REGISTRY_SERVICE_NAME` holds; if it ever disagrees with the template, registration fails silently (`ServiceRegistrar:69-71`). |
| `%R~i2vEmb:*` | model | Only when `recsys.model.item-embeddings-source=redis`; the default is `classpath` (`ModelRuntimeProvider:249-251`). |
| `~submit_token:*` | model | `RECSYS_SUBMIT_TOKEN_ENABLED` defaults to `false` (`application.yml:128`). |

### 6d. Not needed by any workload

`dlock:*`, `mutex:*`, `wdlock:*`, and a second `topk:` writer — the key spaces of
`RedisDistributedLock`, `RedisMutex`, `WatchdogLock` and `RedisTopKStore`, none of which is
constructed anywhere in `src/main/java`. The template grants none of them today; this is recorded
so a future sweep does not add them.

---

## 7. Cross-references

- `docs/system_design/20_AuthN_AuthZ.md` — the credential inventory, including the Redis transport
  credential this ACL work subdivides.
- `docs/runbooks/redis-auth.md` — how `REDIS_PASSWORD` / `REDIS_ALLOW_NO_AUTH` behave, and the
  per-user rotation procedure.
- `docs/system_design/03_DB_Scaling_Sharding.md` — the `sr:` key formats and generation prefixes
  that make `~sr:*` the only safe pattern.
- `docs/system_design/11_Service_Discovery.md` — the opt-in `svc:registry:` namespace.
- `docs/superpowers/specs/2026-08-09-redis-per-service-acl-design.md` — the branch design and its
  measured-behaviour review, which asked for exactly this consumer-first census.
