# Spec: Redis Pool Validation + Lock/Fallback Reliability

## Objective
Harden three reliability gaps the audit found: stale pooled connections, one watchdog thread per distributed lock, and an empty popularity result when Redis is unavailable. No behavior change on the happy path.

## Scope

### A. Validate idle/returned pooled connections
File: `src/main/java/com/recsys/infrastructure/redis/RedisConnectionFactory.java` (~lines 210-227).
Current `GenericObjectPoolConfig` sets `testOnBorrow` (env-gated) but **not** `testOnReturn` or `testWhileIdle`, and configures no idle-eviction sweep — so a connection idle through a server-side timeout can be handed out stale.
Add (env-gated, conservative defaults):
- `setTestWhileIdle(true)`
- `setTimeBetweenEvictionRuns(Duration)` (e.g. 30s) and `setMinEvictableIdleTime(Duration)` (e.g. 60s)
- `setNumTestsPerEvictionRun(-1)` (test all idle)
- Keep `testOnReturn` **off** by default (adds a PING per return; expose env `REDIS_POOL_TEST_ON_RETURN=false`).
Apply consistently in both the `ReplicaConfig`-driven and env-driven pool builders.
- Preserve: existing env knobs and defaults; only add new ones.

### B. Shared watchdog executor for distributed locks
File: `src/main/java/com/recsys/infrastructure/lock/WatchdogLock.java` (~line 117).
Each lock currently creates its own `Executors.newSingleThreadScheduledExecutor` — O(locks) daemon threads under concurrency. Introduce one shared `ScheduledExecutorService` (sized small, e.g. 1-2 threads, daemon) used by all `WatchdogLock` instances for renewal; schedule per-lock renewal tasks on it and cancel them on release.
- Decision for the plan: a static shared executor vs an injected one. Prefer injectable (constructor default = shared singleton) so tests can supply a deterministic executor.
- Preserve: renewal cadence, lease semantics, release/cancel behavior, and the existing test that asserts renewal happens.

### C. `GlobalPopularityStore` fallback when Redis + stale cache are both empty
File: `src/main/java/com/recsys/infrastructure/redis/GlobalPopularityStore.java` (`getTopIds`, ~line 37; backed by `TtlSingleFlightCache`).
If Redis is down AND no stale snapshot exists (cold start during an outage), `getTopIds` returns empty → zero recommendations for channels relying on it. Add a fallback: an optional seed list (from the file-system popularity seed already loaded at startup) used only when the cache yields nothing.
- Preserve: normal path returns the Redis-backed top-N exactly as today; fallback engages only on empty.

## Out of Scope
- Replacing Jedis with Lettuce or changing the pool library.
- Lock algorithm changes (SETNX/Lua) beyond executor sharing.

## Testing
- Pool config: unit test asserting the new validation flags are set from env (mirror existing `RedisConnectionFactoryTest`).
- WatchdogLock: existing renewal test passes; add a test that N locks share one executor (thread count does not scale with lock count) and that release cancels the renewal task.
- GlobalPopularityStore: test that with an empty/failing cache + a configured seed, `getTopIds` returns the seed; with a populated cache it returns Redis data (seed ignored).
- `mvn clean test` green.

## Risks
- Idle eviction adds background PINGs — keep cadence conservative and env-tunable.
- A shared executor is a new lifecycle object — ensure it is daemon and shut down on application stop (or never blocks JVM exit).

## Success
- Pooled connections are validated while idle; lock watchdog threads are O(1) not O(locks); popularity has a non-empty fallback during a cold Redis outage; existing tests green.
