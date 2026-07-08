# AZ-Aware Redis Reads (gap #2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make production Redis reads route to a replica (the ElastiCache reader endpoint) so they survive a primary-AZ failover, by wiring the existing `RedisReadReplicaRouter` into the read paths via a small adapter.

**Architecture:** Add a `RoutingRedisExecutor` (a `RedisExecutor` whose `executeRead` goes to a replica and `execute`/`executePipelined` to the primary) wrapping `RedisReadReplicaRouter`; build it from a new `LettuceClientFactory.routingFromEnv()`; swap the four entry points that construct Redis from `fromEnv()` to `routingFromEnv()`; convert the two `RedisEmbeddingStore` reads to `executeRead()`; point `REDIS_REPLICA_NODES` at the single ElastiCache reader endpoint. Safe when unset (router with no replicas → primary).

**Tech Stack:** Java 17, Lettuce, JUnit 5 + AssertJ + Mockito, Kustomize. Build needs JDK 17.

## Global Constraints

- Continues on branch `feat/zonal-hardening` (updates PR #177). Never merge to main directly.
- Build/test with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`.
- **Behavior MUST be unchanged when `REDIS_REPLICA_NODES` is unset** (every non-EKS profile): the router then has no replicas and `readable()` returns the primary. Do not break this.
- Reads routed to replicas (embeddings/topk/features) become eventually consistent — acceptable. Consistency-sensitive reads (`SequenceGenerator`, `ShardTopologyStore`, `LoginTokenService`, `SubmitTokenService`) MUST stay on `execute()` → primary. Do not convert those.
- Use the single ElastiCache **reader endpoint** for `REDIS_REPLICA_NODES` (format `host:port`, az defaults to `unknown`) — no `AWS_AZ` injection in this change.
- `RedisExecutor` method signatures are generic: `<T> T execute(Function<RedisCommands<String,String>,T>)`, `<T> T executeRead(...)`, `void executePipelined(Consumer<StatefulRedisConnection<String,String>>)`, `void close()`.

---

### Task 1: Add `RoutingRedisExecutor` adapter + factory methods (with unit test)

Create the read/write-splitting executor and the factory entry points that build it. No call site changes yet — this task is self-contained and independently testable.

**Files:**
- Create: `src/main/java/com/recsys/infrastructure/redis/RoutingRedisExecutor.java`
- Modify: `src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java` (add routing methods; DRY the router-timeout variant)
- Create: `src/test/java/com/recsys/infrastructure/redis/RoutingRedisExecutorTest.java`

**Interfaces:**
- Consumes: `RedisReadReplicaRouter` (`writable()`→primary `RedisExecutor`, `readable()`→replica-or-primary `RedisExecutor`, `close()`), and the existing `LettuceClientFactory.routerFromEnv(Map)` / `uriFromEnv(env,maxTimeoutMs)` (already caps via `Math.min`).
- Produces: `RoutingRedisExecutor implements RedisExecutor`; `LettuceClientFactory.routingFromEnv()` and `routingFromEnv(int maxTimeoutMs)` returning `RedisExecutor`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/redis/RoutingRedisExecutorTest.java`:
```java
package com.recsys.infrastructure.redis;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingRedisExecutorTest {

    @SuppressWarnings("unchecked")
    private static Function<RedisCommands<String, String>, String> anyFn() {
        return c -> "ignored";
    }

    @Test
    void execute_goesToPrimary() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        when(primary.execute(any())).thenReturn("PRIMARY");
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");
        try (var exec = new RoutingRedisExecutor(router)) {
            assertThat(exec.execute(anyFn())).isEqualTo("PRIMARY");
        }
        verify(primary).execute(any());
        verify(replica, never()).execute(any());
        verify(replica, never()).executeRead(any());
    }

    @Test
    void executeRead_goesToReplicaWhenConfigured() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        when(replica.executeRead(any())).thenReturn("REPLICA");
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");
        try (var exec = new RoutingRedisExecutor(router)) {
            assertThat(exec.executeRead(anyFn())).isEqualTo("REPLICA");
        }
        verify(replica).executeRead(any());
        verify(primary, never()).execute(any());
        verify(primary, never()).executeRead(any());
    }

    @Test
    void executeRead_fallsBackToPrimaryWhenNoReplicas() {
        RedisExecutor primary = mock(RedisExecutor.class);
        when(primary.executeRead(any())).thenReturn("PRIMARY_READ");
        var router = new RedisReadReplicaRouter(primary, List.of(), "us-east-1a");
        try (var exec = new RoutingRedisExecutor(router)) {
            assertThat(exec.executeRead(anyFn())).isEqualTo("PRIMARY_READ");
        }
        verify(primary).executeRead(any());
    }

    @Test
    void executePipelined_goesToPrimary() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");
        try (var exec = new RoutingRedisExecutor(router)) {
            exec.executePipelined(conn -> { /* no-op */ });
        }
        verify(primary).executePipelined(any());
        verify(replica, never()).executePipelined(any());
    }

    @Test
    void close_closesRouterAndAllExecutors() {
        RedisExecutor primary = mock(RedisExecutor.class);
        RedisExecutor replica = mock(RedisExecutor.class);
        var router = new RedisReadReplicaRouter(primary,
                List.of(new RedisReadReplicaRouter.AzExecutor(replica, "us-east-1b")), "us-east-1b");
        new RoutingRedisExecutor(router).close();
        verify(primary).close();
        verify(replica).close();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile (class missing)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=RoutingRedisExecutorTest`
Expected: FAIL — compilation error, `RoutingRedisExecutor` does not exist.

- [ ] **Step 3: Create the adapter**

Create `src/main/java/com/recsys/infrastructure/redis/RoutingRedisExecutor.java`:
```java
package com.recsys.infrastructure.redis;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A {@link RedisExecutor} that splits reads from writes across a
 * {@link RedisReadReplicaRouter}: mutations and pipelines go to the primary
 * (write leader), while {@link #executeRead} is routed to a read replica when one
 * is configured (falling back to the primary otherwise).
 *
 * <p>This is the adapter that lets existing call sites — which already distinguish
 * {@code execute} (writes) from {@code executeRead} (reads) — transparently route
 * reads to replicas without any further code change. When no replicas are
 * configured, the wrapped router returns the primary for reads too, so behavior is
 * identical to a single-endpoint executor.
 */
public final class RoutingRedisExecutor implements RedisExecutor {

    private final RedisReadReplicaRouter router;

    public RoutingRedisExecutor(RedisReadReplicaRouter router) {
        this.router = router;
    }

    @Override
    public <T> T execute(Function<RedisCommands<String, String>, T> fn) {
        return router.writable().execute(fn);
    }

    @Override
    public <T> T executeRead(Function<RedisCommands<String, String>, T> fn) {
        return router.readable().executeRead(fn);
    }

    @Override
    public void executePipelined(Consumer<StatefulRedisConnection<String, String>> fn) {
        router.writable().executePipelined(fn);
    }

    @Override
    public void close() {
        router.close();
    }
}
```

- [ ] **Step 4: Add the factory methods (and DRY the router timeout variant)**

In `src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java`, replace the existing single-arg `routerFromEnv(Map<String, String> env)` method (currently at ~lines 59-78) with a delegating pair that adds a `maxTimeoutMs` cap:
```java
    static RedisReadReplicaRouter routerFromEnv(Map<String, String> env) {
        return routerFromEnv(env, Integer.MAX_VALUE);
    }

    static RedisReadReplicaRouter routerFromEnv(Map<String, String> env, int maxTimeoutMs) {
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolCfg = poolConfig(defaultPoolKnobs(env));
        int timeoutMs = Math.min(readPositiveInt(env, "REDIS_TIMEOUT_MS", DEFAULT_TIMEOUT_MS), maxTimeoutMs);
        String password = env.getOrDefault("REDIS_PASSWORD", "");
        RedisExecutor primary = executor(uriFromEnv(env, maxTimeoutMs), poolCfg);
        String localAz = env.getOrDefault("AWS_AZ", env.getOrDefault("AVAILABILITY_ZONE", "unknown"));

        List<RedisReadReplicaRouter.AzExecutor> replicas = new ArrayList<>();
        String spec = env.getOrDefault("REDIS_REPLICA_NODES", "");
        if (!spec.isBlank()) {
            for (String node : spec.split(",")) {
                node = node.strip();
                if (node.isEmpty()) continue;
                ReplicaConfig cfg = ReplicaConfig.parse(node);
                RedisURI uri = standaloneUri(cfg.host(), cfg.port(), password, timeoutMs);
                replicas.add(new RedisReadReplicaRouter.AzExecutor(executor(uri, poolCfg), cfg.az()));
            }
        }
        return new RedisReadReplicaRouter(primary, replicas, localAz);
    }
```
(This preserves the previous behavior: with `maxTimeoutMs = Integer.MAX_VALUE`, `timeoutMs` reduces to `REDIS_TIMEOUT_MS` and the primary URI is uncapped, exactly as before.)

Then add the routing-executor factory methods right after the `fromEnv(int maxTimeoutMs)` / `from(...)` block (in the "Single executors" section, before the "Routers" section):
```java
    // ── Routing executors (execute→primary, executeRead→replica) ──────────────

    /** A read/write-splitting executor: reads use a replica (reader endpoint) when
     *  {@code REDIS_REPLICA_NODES} is set, writes use the primary. */
    public static RedisExecutor routingFromEnv() {
        return new RoutingRedisExecutor(routerFromEnv(System.getenv()));
    }

    /** Latency-capped routing variant (recall pool): caps primary and replica
     *  command timeouts to {@code maxTimeoutMs}. */
    public static RedisExecutor routingFromEnv(int maxTimeoutMs) {
        return new RoutingRedisExecutor(routerFromEnv(System.getenv(), maxTimeoutMs));
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=RoutingRedisExecutorTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Confirm the existing router test still passes (no regression from the DRY refactor)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test -Dtest=RedisReadReplicaRouterTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/redis/RoutingRedisExecutor.java \
        src/main/java/com/recsys/infrastructure/redis/LettuceClientFactory.java \
        src/test/java/com/recsys/infrastructure/redis/RoutingRedisExecutorTest.java
git commit -m "feat(redis): add RoutingRedisExecutor (executeRead->replica, execute->primary)

Wraps RedisReadReplicaRouter as a RedisExecutor so read call sites route to a
replica; adds LettuceClientFactory.routingFromEnv() (+ timeout variant). Safe
when REDIS_REPLICA_NODES is unset (router falls back to primary).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Wire the routing executor into the read paths

Swap the four entry points to `routingFromEnv()` and convert the two `RedisEmbeddingStore` reads to `executeRead()`. The topk/feature/record stores already call `executeRead()`, so they route automatically.

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java:63`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java:65`
- Modify: `src/main/java/com/recsys/application/model/ModelRuntimeProvider.java:159` and `:253`
- Modify: `src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java:78` and `:140`

**Interfaces:**
- Consumes: `LettuceClientFactory.routingFromEnv()` / `routingFromEnv(int)` from Task 1.
- Produces: all four Redis entry points hand the stores a `RoutingRedisExecutor`; `RedisEmbeddingStore` reads (`get`, `mget`) call `executeRead()`.

- [ ] **Step 1: Swap `RecSysServer`**

In `src/main/java/com/recsys/api/serving/RecSysServer.java:63`, change:
```java
        RedisExecutor jedisPool = LettuceClientFactory.fromEnv();
```
to:
```java
        RedisExecutor jedisPool = LettuceClientFactory.routingFromEnv();
```

- [ ] **Step 2: Swap `OnlinePredictionServer`**

In `src/main/java/com/recsys/api/online/OnlinePredictionServer.java:65`, change the identical line `LettuceClientFactory.fromEnv()` to `LettuceClientFactory.routingFromEnv()`.

- [ ] **Step 3: Swap `ModelRuntimeProvider` (both pools)**

In `src/main/java/com/recsys/application/model/ModelRuntimeProvider.java`:
- Line 159: `LettuceClientFactory.fromEnv(RECALL_REDIS_TIMEOUT_MS)` → `LettuceClientFactory.routingFromEnv(RECALL_REDIS_TIMEOUT_MS)`
- Line 253: `LettuceClientFactory.fromEnv()` → `LettuceClientFactory.routingFromEnv()`

- [ ] **Step 4: Convert the two embedding reads to `executeRead`**

In `src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java`:
- Line 78, change `exec.execute(c -> c.get(keyPrefix + ":" + movieId))` to `exec.executeRead(c -> c.get(keyPrefix + ":" + movieId))`
- Line 140, change `exec.execute(c -> c.mget(keys))` to `exec.executeRead(c -> c.mget(keys))`

Leave the `set(...)` writes (lines 84, 95) and the other `execute(...)` blocks (161, 206) unchanged — only the `get`/`mget` single reads convert.

- [ ] **Step 5: Compile and run the full suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test`
Expected: BUILD SUCCESS, all tests pass (no behavior change when `REDIS_REPLICA_NODES` is unset — the test env does not set it, so reads still hit the primary and existing store tests are unaffected).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecSysServer.java \
        src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
        src/main/java/com/recsys/application/model/ModelRuntimeProvider.java \
        src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java
git commit -m "feat(redis): route reads to replicas at the four Redis entry points

Swap RecSysServer, OnlinePredictionServer, and ModelRuntimeProvider (recall +
item-embedding pools) to routingFromEnv(); convert RedisEmbeddingStore get/mget
to executeRead(). Topk/feature/record stores already use executeRead().

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Point `REDIS_REPLICA_NODES` at the ElastiCache reader endpoint

Re-add the config, now that it is actually consumed.

**Files:**
- Modify: `k8s/eks/redis-elasticache-patch.yaml`

**Interfaces:**
- Consumes: the wired read path from Tasks 1-2 (`routingFromEnv()` reads `REDIS_REPLICA_NODES`).
- Produces: `kubectl kustomize k8s/eks` renders `recsys-config` with `REDIS_REPLICA_NODES` = the reader endpoint.

- [ ] **Step 1: Add the reader-endpoint config**

In `k8s/eks/redis-elasticache-patch.yaml`, add to the `data:` block after `REDIS_PORT`:
```yaml
  # AZ-aware reads: the single ElastiCache READER endpoint (host:port). Reads
  # (executeRead) route here via RoutingRedisExecutor and survive a primary-AZ
  # failover; the reader endpoint auto-ejects failed replicas. az defaults to
  # "unknown" (no same-AZ locality — that is an optional later optimization).
  REDIS_REPLICA_NODES: "<elasticache-reader-endpoint>.cache.amazonaws.com:6379"
```
Also add to the file's top comment block (after the "Automatic failover: enabled" line):
```yaml
#   - A reader endpoint provisioned for the replicas, referenced by
#     REDIS_REPLICA_NODES below. Reads route there and survive a primary-AZ loss.
```

- [ ] **Step 2: Render and assert**

Run:
```bash
kubectl kustomize k8s/eks | grep 'REDIS_REPLICA_NODES'
kubectl kustomize k8s/eks | grep -c 'REDIS_REPLICA_NODES'
```
Expected: one line showing the reader-endpoint value; count = 1.

- [ ] **Step 3: Commit**

```bash
git add k8s/eks/redis-elasticache-patch.yaml
git commit -m "feat(k8s): set REDIS_REPLICA_NODES to the ElastiCache reader endpoint (us-east-1)

Now consumed by the wired read path (RoutingRedisExecutor): reads route to the
reader endpoint and survive a primary-AZ failover.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Update the runbook — gap #2 delivered

Flip the zonal-resilience runbook from "gap #2 is a follow-up" to "delivered."

**Files:**
- Modify: `docs/runbooks/zonal-resilience.md`

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: a runbook whose read-path claims match the shipped behavior.

- [ ] **Step 1: Update the runbook claims**

In `docs/runbooks/zonal-resilience.md`, make these edits (read the file first to match exact wording):
- **Required infrastructure** — in the ElastiCache bullet, restore that reads use a reader endpoint: "ElastiCache Multi-AZ + automatic failover enabled, with a **reader endpoint** configured via `REDIS_REPLICA_NODES` (reads route there via `RoutingRedisExecutor`)."
- **What survives a single-AZ loss automatically** — add a bullet: "**Redis reads** — routed to the ElastiCache reader endpoint via `RoutingRedisExecutor`; a primary-AZ loss no longer stalls reads (writes still ride the ~30s primary DNS failover)."
- **Expected degradation profile** — change to: reads route to the reader endpoint and survive a primary-AZ loss; **writes** ride the ~30s ElastiCache primary DNS flip when the primary's AZ is lost; the online path's stale-TTL caches absorb residual read impact.
- **Operator checklist step 4** — change to: if the primary's AZ was lost, expect a ~30s **write** blip during the ElastiCache DNS failover; reads continue via the reader endpoint.
- **Follow-up section** — replace the "wire the router" follow-up with: "Same-AZ read locality (per-node endpoints + `AWS_AZ` injection) is an optional cross-AZ-cost optimization; reads already survive via the reader endpoint."

- [ ] **Step 2: Verify no stale "deferred/not wired" claims remain**

Run:
```bash
grep -rniE 'not yet wired|deferred|follow-up.*router|no read path calls' docs/runbooks/zonal-resilience.md || echo "clean"
```
Expected: `clean` (or only the optional same-AZ-locality follow-up, which is fine).

- [ ] **Step 3: Commit**

```bash
git add docs/runbooks/zonal-resilience.md
git commit -m "docs(zonal): runbook — AZ-aware reads delivered via reader endpoint

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Final verification + update PR

**Files:** none.

- [ ] **Step 1: Full suite + renders**

Run:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test 2>&1 | grep -E 'Tests run: [0-9]+, Failures|BUILD SUCCESS|BUILD FAILURE' | tail -3
kubectl kustomize k8s/eks >/dev/null && echo "eks OK"
kubectl kustomize k8s/base >/dev/null && echo "base OK"
```
Expected: final `Tests run:` line with `Failures: 0, Errors: 0`, `BUILD SUCCESS`; `eks OK`; `base OK`.

- [ ] **Step 2: Confirm the wiring is complete**

Run:
```bash
grep -rn 'routingFromEnv' src/main/java | wc -l          # expect 4 call sites + 2 factory defs = 6
grep -c 'REDIS_REPLICA_NODES' <(kubectl kustomize k8s/eks)  # expect 1
grep -n 'executeRead' src/main/java/com/recsys/infrastructure/redis/RedisEmbeddingStore.java  # get + mget
```
Expected: `routingFromEnv` appears 6 times (2 factory method defs + 4 entry-point calls); `REDIS_REPLICA_NODES` renders once; `RedisEmbeddingStore` shows two `executeRead` lines.

- [ ] **Step 3: Push (updates PR #177)**

```bash
git push
```
Expected: the new commits push to `origin/feat/zonal-hardening`, updating PR #177. (No new PR needed.)

---

## Self-Review

**Spec coverage:**
- `RoutingRedisExecutor` adapter (execute→primary, executeRead→replica, close→router) → Task 1. ✓
- Factory `routingFromEnv()` + timeout variant, DRY router build → Task 1. ✓
- Four entry-point swaps → Task 2. ✓
- `RedisEmbeddingStore` get/mget → executeRead → Task 2. ✓
- Consistency-sensitive reads stay on primary → constraint honored (Task 2 explicitly leaves them). ✓
- `REDIS_REPLICA_NODES` = reader endpoint → Task 3. ✓
- Runbook flipped to delivered → Task 4. ✓
- Safe when unset → covered by Task 1 test `executeRead_fallsBackToPrimaryWhenNoReplicas` and Task 2 Step 5 (suite green with no `REDIS_REPLICA_NODES`). ✓
- New unit test for the adapter → Task 1. ✓

**Placeholder scan:** No TBD/TODO. `<elasticache-reader-endpoint>` follows the existing `<...>` out-of-band convention.

**Type/consistency:** `routingFromEnv()`/`routingFromEnv(int)` return `RedisExecutor`; entry points assign to `RedisExecutor` (unchanged types). `routerFromEnv(env)` delegates to `routerFromEnv(env, Integer.MAX_VALUE)` preserving prior timeout behavior (verified: `Math.min(REDIS_TIMEOUT_MS, MAX)` = `REDIS_TIMEOUT_MS`). Adapter method signatures match the `RedisExecutor` interface exactly.
