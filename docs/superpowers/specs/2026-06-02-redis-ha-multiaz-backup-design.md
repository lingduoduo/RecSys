# Redis HA, Multi-AZ, and Backup Design

**Date:** 2026-06-02
**Status:** Approved

## Overview

Add failover, multi-AZ resilience, and RDB snapshot backups to the Redis layer of the RecSys Backend Service. The system currently uses a single-node `JedisPool(host, port)` with no replication, no persistence, and no failover.

The chosen approach (Approach A) uses:
- **Local/dev**: Redis Sentinel cluster (1 primary + 2 replicas + 3 sentinel pods) in Docker Compose and K8s base
- **Prod (EKS)**: AWS ElastiCache for Redis in Multi-AZ mode with automatic failover; app uses a plain `JedisPool` pointed at the ElastiCache primary endpoint (DNS-based failover, ~30s window)
- **Client**: New `RedisConnectionFactory` abstracts `JedisSentinelPool` vs. `JedisPool` behind the `Pool<Jedis>` supertype
- **Backups**: RDB snapshots (`save 900 1 / 300 10 / 60 10000`) on the primary; ElastiCache automated daily snapshots in prod

---

## Section 1: App Layer

### 1.1 New class: `RedisConnectionFactory`

**Package**: `com.recsys.infrastructure.redis`

A static factory that reads env vars and returns the correct Jedis pool type:

```
REDIS_MODE=sentinel  → JedisSentinelPool(masterName, sentinelNodes, poolConfig)
REDIS_MODE=standalone → JedisPool(host, port, poolConfig)   (default)
```

**Env vars consumed:**

| Var | Sentinel mode | Standalone mode | Default |
|---|---|---|---|
| `REDIS_MODE` | `sentinel` | `standalone` | `standalone` |
| `REDIS_SENTINEL_MASTER` | master name | ignored | `mymaster` |
| `REDIS_SENTINEL_NODES` | comma-separated `host:port` | ignored | _(empty)_ |
| `REDIS_HOST` | ignored | primary host | `localhost` |
| `REDIS_PORT` | ignored | primary port | `6379` |

**Pool config** (`JedisPoolConfig`): `maxTotal=50`, `maxIdle=10`, `minIdle=2`, `testOnBorrow=true`, `blockWhenExhausted=true`.

**Return type**: `Pool<Jedis>` — the common supertype of both `JedisPool` and `JedisSentinelPool`. All call sites use `pool.getResource()` which works identically on both.

### 1.2 Type migration: `JedisPool` → `Pool<Jedis>`

Eight consumer classes change their constructor parameter and field type. No logic changes — only the declared type widens:

| Class | Package |
|---|---|
| `RedisEmbeddingStore` | `infrastructure.redis` |
| `RedisTopKStore` | `infrastructure.redis` |
| `ShardedTopKStore` | `infrastructure.redis` |
| `OnlineFeatureStore` | `streaming` |
| `RedisMutex` | `streaming` |
| `RedisRateLimiter` | `streaming` |
| `WatchdogLock` | `streaming` |
| `RedisDistributedLock` | `streaming` |

### 1.3 Server entry points

**`RecSysServer.run()`** and **`OnlinePredictionServer.main()`**: replace
```java
new JedisPool(redisHost, redisPort)
```
with
```java
RedisConnectionFactory.fromEnv()
```
The `try-with-resources` block already calls `close()` on the pool at shutdown — no lifecycle change needed.

**`ModelRuntimeProvider`** (Spring Boot `@Service`):
- Remove `@Value`-injected `redisHost` and `redisPort` fields (factory reads env vars directly)
- `redisItemEmbeddingStoreIfEnabled()`: `new JedisPool(redisHost, redisPort)` → `RedisConnectionFactory.fromEnv()`
- Field type: `JedisPool redisItemEmbeddingPool` → `Pool<Jedis> redisItemEmbeddingPool`
- `@PreDestroy` closes the pool (already present, no change needed)

**`SubmitTokenService`** (Spring Boot `@Service`):
- `Supplier<JedisPool> poolFactory` → `Supplier<Pool<Jedis>> poolFactory`
- `volatile JedisPool pool` → `volatile Pool<Jedis> pool`
- Public constructor: `() -> new JedisPool(...)` → `() -> RedisConnectionFactory.fromEnv()`
- Package-private test constructor remains: callers pass a `Supplier<Pool<Jedis>>` (testability preserved)
- `SubmitTokenProperties`: remove `redisHost` and `redisPort` fields and their `application.yml` bindings — the factory reads env vars, not Spring properties

---

## Section 2: Configuration & Env Vars

### 2.1 New env vars

Three vars are added to every configuration surface that sets Redis connection info:

```
REDIS_MODE=sentinel            # or standalone
REDIS_SENTINEL_MASTER=mymaster
REDIS_SENTINEL_NODES=redis-sentinel:26379
```

### 2.2 K8s base `configmap.yaml`

Add the three new vars (sentinel values — base targets local/dev cluster):

```yaml
REDIS_MODE: "sentinel"
REDIS_SENTINEL_MASTER: "mymaster"
REDIS_SENTINEL_NODES: "redis-sentinel:26379"
```

Existing `REDIS_HOST` and `REDIS_PORT` keys remain for backward compat (used when `REDIS_MODE=standalone`).

### 2.3 Spring Boot `application.yml`

Remove the `recsys.model.redis.host` / `recsys.model.redis.port` and `recsys.submit-token.redis-host` / `recsys.submit-token.redis-port` bindings. Connection config now flows exclusively through env vars into `RedisConnectionFactory.fromEnv()`.

### 2.4 `SubmitTokenProperties`

Remove `redisHost` and `redisPort` fields (and getters/setters). The class retains `enabled`, `ttlSeconds`, and `keyPrefix`.

---

## Section 3: K8s Base — Redis Sentinel Cluster

Replace the current single-pod `redis.yaml` with a multi-resource manifest.

### 3.1 Resources

**`redis-primary` StatefulSet** (1 replica)
- Image: `redis:7-alpine`
- Args: `--save 900 1 --save 300 10 --save 60 10000 --maxmemory 200mb --maxmemory-policy allkeys-lru`
- PersistentVolumeClaim: `redis-primary-data`, `1Gi`, mounted at `/data`
- Service: `redis-primary` ClusterIP, port 6379

**`redis-replica` StatefulSet** (2 replicas)
- Image: `redis:7-alpine`
- Args: `--replicaof redis-primary 6379 --maxmemory 200mb --maxmemory-policy allkeys-lru`
- No PVC (replicas are rebuilt from primary on restart)
- Service: `redis-replica` ClusterIP, port 6379 (read endpoint, reserved for future use)

**`redis-sentinel` Deployment** (3 replicas)
- Image: `redis:7-alpine`
- Command: `redis-sentinel /etc/redis/sentinel.conf`
- Sentinel config mounted from ConfigMap (`redis-sentinel-config`):
  ```
  sentinel monitor mymaster redis-primary 6379 2
  sentinel down-after-milliseconds mymaster 5000
  sentinel failover-timeout mymaster 30000
  sentinel parallel-syncs mymaster 1
  ```
- Service: `redis-sentinel` ClusterIP, port 26379

### 3.2 Multi-AZ placement

All three StatefulSets and the Sentinel Deployment use `podAntiAffinity` with `preferredDuringSchedulingIgnoredDuringExecution` on `topology.kubernetes.io/zone`. This spreads primary, replicas, and sentinels across AZs so a single-AZ failure:
- Does not lose the primary and both replicas simultaneously
- Does not drop quorum (at least 2 of 3 sentinels remain reachable)

### 3.3 `kustomization.yaml`

Updated resource list to reference the new manifest file(s). The old single-file `redis.yaml` is replaced.

---

## Section 4: Docker Compose — Local Sentinel

Update `docker-compose.streaming.yml`:

### 4.1 Remove

The existing `redis` service (`redis-dev`, single node, no persistence).

### 4.2 Add

**`redis-primary`**: `redis:7-alpine`, port `6379:6379`, named volume `redis-primary-data`, RDB args `--save 900 1 --save 300 10`.

**`redis-replica`**: `redis:7-alpine`, no exposed port, `--replicaof redis-primary 6379`. `depends_on: redis-primary`.

**`redis-sentinel-1`, `redis-sentinel-2`, `redis-sentinel-3`**: each `redis:7-alpine` running `redis-sentinel /etc/redis/sentinel.conf`. Sentinel config is a file committed at `docker/redis/sentinel.conf` and bind-mounted read-only into all three containers.

**`docker/redis/sentinel.conf`** (new file):
```
sentinel monitor mymaster redis-primary 6379 2
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 30000
sentinel parallel-syncs mymaster 1
```

### 4.3 Env vars for compose services

Any service in the compose file that currently sets `REDIS_HOST` gets three additional vars:
```yaml
REDIS_MODE: sentinel
REDIS_SENTINEL_MASTER: mymaster
REDIS_SENTINEL_NODES: redis-sentinel-1:26379,redis-sentinel-2:26379,redis-sentinel-3:26379
```

---

## Section 5: EKS Overlay — ElastiCache Prod

### 5.1 New patch file: `k8s/eks/redis-elasticache-patch.yaml`

A strategic merge patch on the `recsys-config` ConfigMap:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: recsys-config
  namespace: recsys
data:
  REDIS_MODE: "standalone"
  # Set REDIS_HOST to your ElastiCache primary endpoint before deploying.
  # Required AWS-side config:
  #   - Multi-AZ: enabled
  #   - Automatic failover: enabled
  #   - Snapshot retention: >= 1 day
  #   - Preferred snapshot window: off-peak hours
  REDIS_HOST: "<elasticache-primary-endpoint>.cache.amazonaws.com"
  REDIS_PORT: "6379"
  REDIS_SENTINEL_MASTER: ""
  REDIS_SENTINEL_NODES: ""
```

### 5.2 Exclude base Redis resources in EKS overlay

The base `kustomization.yaml` references a separate `redis-cluster.yaml` file (containing the StatefulSets, Deployment, and Services from Section 3) rather than including them inline in a shared manifest. The EKS overlay `kustomization.yaml` simply omits `redis-cluster.yaml` from its `resources:` list — Kustomize only includes resources explicitly listed, so the Sentinel cluster is never rendered in the EKS overlay. ElastiCache replaces those resources entirely.

### 5.3 Failover behavior in prod

ElastiCache promotes a replica to primary and flips the primary endpoint DNS within ~30s. The app's `JedisPool` will see connection errors during that window; existing connection-pool retry behavior (Jedis throws `JedisConnectionException` on broken connections, which callers already handle as errors) applies. No additional client logic is needed.

---

## Files Changed / Created

| File | Action |
|---|---|
| `src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java` | Create |
| `src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java` | Edit (`JedisPool` → `Pool<Jedis>`) |
| `src/main/java/com/recsys/infrastructure/redis/RedisTopKStore.java` | Edit |
| `src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java` | Edit |
| `src/main/java/com/recsys/streaming/OnlineFeatureStore.java` | Edit |
| `src/main/java/com/recsys/streaming/RedisMutex.java` | Edit |
| `src/main/java/com/recsys/streaming/RedisRateLimiter.java` | Edit |
| `src/main/java/com/recsys/streaming/WatchdogLock.java` | Edit |
| `src/main/java/com/recsys/streaming/RedisDistributedLock.java` | Edit |
| `src/main/java/com/recsys/serving/RecSysServer.java` | Edit |
| `src/main/java/com/recsys/streaming/OnlinePredictionServer.java` | Edit |
| `src/main/java/com/recsys/modelbased/service/ModelRuntimeProvider.java` | Edit |
| `src/main/java/com/recsys/modelbased/service/SubmitTokenService.java` | Edit |
| `src/main/java/com/recsys/modelbased/config/SubmitTokenProperties.java` | Edit |
| `src/main/resources/application.yml` | Edit |
| `k8s/base/redis.yaml` | Rewrite |
| `k8s/base/configmap.yaml` | Edit |
| `k8s/base/kustomization.yaml` | Edit |
| `k8s/eks/redis-elasticache-patch.yaml` | Create |
| `k8s/eks/kustomization.yaml` | Edit |
| `docker-compose.streaming.yml` | Edit |
| `docker/redis/sentinel.conf` | Create |

---

## Out of Scope

- Redis Cluster mode (overkill for current data volume; single-primary Sentinel is sufficient)
- AOF persistence (RDB snapshots provide adequate durability given Flink can replay online features)
- Mutual TLS between Redis nodes (network policy isolation is the existing security boundary)
- Read replicas for app traffic (replica service is provisioned but app routes all reads to primary)
