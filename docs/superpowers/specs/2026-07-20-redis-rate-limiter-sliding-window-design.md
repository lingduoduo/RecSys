# Redis Rate Limiter — Sliding Window + Precise Global Ceiling Design

## Objective

The online serving path's global rate limiter (`RedisRateLimiter`, the only
cluster-wide limit; default 200 QPS) over-admits from two independent causes:

1. **Fixed-window boundary.** The current Lua does `INCR`/`EXPIRE` on a
   per-window key and rejects when the count exceeds `limit`
   (`RedisRateLimiter.java:32-42`). A burst straddling a window reset can admit
   up to ~2× `limit` within any rolling window-length span.
2. **Per-instance local fast-path.** Each instance allows the first
   `localPassThreshold = 0.7 × limit` requests of a window **without consulting
   Redis** (`RedisRateLimiter.java:126-129`). Across N serving instances, up to
   `N × 0.7 × limit` requests pass before the global limit is enforced at all —
   at 8 instances, ~5.6× the global ceiling.

This design makes the global ceiling actually hold: replace the fixed-window
algorithm with a **weighted sliding-window counter** (fixes cause 1) and
**remove the local fast-path so every request consults Redis** (fixes cause 2).
The eval volume is small — the global limit is ~200 QPS — and the existing
fail-open + circuit breaker already guard Redis outages.

## Scope

In scope:

- Rewrite `RedisRateLimiter`'s Lua to a sliding-window-counter algorithm.
- Remove the local fast-path (`localWindowBucket`, `localCount`,
  `localPassThreshold`, `localPassFraction`).
- Update `Snapshot` (drop `localPassThreshold`) and `OnlineOpsService`'s
  serialization of it.
- Rewrite `RedisRateLimiterTest` (unit) + add a `@Tag("docker")` integration
  test for the sliding-window boundary behavior.

Out of scope (explicit non-goals):

- The other limiters (`GatewayRateLimiter`, `ModelRateLimiter`,
  `LlmTokenRateLimiter`, `TokenBucket`) — unchanged.
- No new env vars; the config model stays QPS + window seconds.
- No change to `tryAcquire(String) → Decision` or the caller
  (`OnlineServices`).
- No change to the circuit breaker (`CircuitBreaker`, 5 failures / 30s reset)
  or fail-open behavior.

## Background

`RedisRateLimiter` is constructed once in `OnlinePredictionServer`
(`new RedisRateLimiter(jedisPool)`), called as `redisRateLimiter.tryAcquire("online")`
in `OnlineServices`, and its `Snapshot` is serialized into `GET /online/ops` by
`OnlineOpsService`. Env: `ONLINE_REDIS_RATE_LIMIT_QPS` (default 0 = disabled),
`ONLINE_REDIS_RATE_LIMIT_WINDOW_SECONDS` (default 1), key prefix `rate:online:`.
Buckets are normalized (`normalizeBucket`) — only `"online"` (→ `rate:online:global`)
is used today.

## Algorithm: weighted sliding-window counter

A single Lua script, evaluated on every enabled request. Time is passed in as
`nowMs` from the application clock (the existing `nowMillis` `LongSupplier` seam,
default `System::currentTimeMillis`) — testable, and avoids relying on Redis
`TIME` (non-deterministic in classic scripting).

```lua
-- KEYS[1] = base bucket key (e.g. "rate:online:global")
-- ARGV[1] = limit, ARGV[2] = windowMs, ARGV[3] = nowMs
local limit    = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local nowMs    = tonumber(ARGV[3])

local windowId = math.floor(nowMs / windowMs)
local elapsed  = nowMs - (windowId * windowMs)
local weight   = (windowMs - elapsed) / windowMs      -- prev-window portion still in view

local curKey  = KEYS[1] .. ':' .. windowId
local prevKey = KEYS[1] .. ':' .. (windowId - 1)
local cur  = tonumber(redis.call('GET', curKey)  or '0')
local prev = tonumber(redis.call('GET', prevKey) or '0')

local estimated = prev * weight + cur
if estimated + 1 > limit then
  local retryMs = windowMs - elapsed
  return {0, 0, math.max(1, math.ceil(retryMs / 1000))}     -- {rejected, remaining, retryAfterS}
end

local newCur = redis.call('INCR', curKey)
if newCur == 1 then
  redis.call('PEXPIRE', curKey, windowMs * 2)               -- lives through next window as "prev"
end
local remaining = limit - (prev * weight + newCur)
if remaining < 0 then remaining = 0 end
return {1, math.floor(remaining), 0}                         -- {allowed, remaining, retryAfterS}
```

Properties:

- **Boundary bounded to ~1×.** As a window rolls, `weight` decays the previous
  window's contribution linearly, so the estimate tracks the true rolling count
  within a small approximation error (standard sliding-window-counter; error is
  bounded and typically far below the fixed-window 2×).
- **O(1) Redis** — two `GET`s plus a conditional `INCR`/`PEXPIRE`, one round trip
  (single `eval`).
- **Self-expiring** — `curKey` gets `PEXPIRE 2×windowMs`, so it persists exactly
  long enough to serve as the previous window, then disappears. No unbounded key
  growth.
- **Retry-after** ≥ 1s on reject (remaining ms in the current window), 0 on
  allow — same `Decision` shape as today.

## Request flow (fast-path removed)

`tryAcquire(bucket)`:

1. `!enabled` → `Decision.allowed(limit, 0, false)` (unchanged).
2. `!circuit.tryAcquire()` → `Decision.allowed(limit, 0, true)` (fail-open while
   OPEN; HALF_OPEN lets only the probe through — unchanged).
3. Otherwise eval the Lua with `(limit, windowMs, nowMs)`. On success
   `circuit.recordSuccess()` and parse the `{allowed, remaining, retryAfter}`
   triple. On exception `circuit.recordFailure()` and fail open
   (`Decision.allowed(limit, 0, true)`), logged — unchanged.

The `localWindowBucket`/`localCount`/`localPassThreshold`/`localPassFraction`
fields and the local pre-check block are deleted. `windowSeconds` is converted to
`windowMs = windowSeconds * 1000L` for the Lua.

## Snapshot / ops changes

`Snapshot` record: `{ enabled, limit, windowSeconds, circuitState }` — the
`localPassThreshold` component is removed, as is the `localPassThreshold()`
getter. `OnlineOpsService` is updated so the `/online/ops` rate-limit section no
longer emits `localPassThreshold`; no other fields change.

## Constructors / test seams

- Public `RedisRateLimiter(RedisExecutor)` — unchanged signature; reads the two
  env vars.
- `disabled()` — unchanged.
- Package-private test constructors: replace the `localPassFraction`-bearing
  overloads with ones that omit it. Keep the `nowMillis` clock seam (now feeds
  the Lua `nowMs` arg) and the circuit-knob seam
  (`circuitFailureThreshold`, `circuitResetMs`). Concretely:
  - `RedisRateLimiter(exec, keyPrefix, limit, windowSeconds)`
  - `RedisRateLimiter(exec, keyPrefix, limit, windowSeconds, nowMillis)`
  - `RedisRateLimiter(exec, keyPrefix, limit, windowSeconds, circuitFailureThreshold, circuitResetMs, nowMillis)`

## Testing

**Unit (mocked `RedisExecutor`, no real Lua):**

- Disabled (`limit=0` or null exec) → always allowed, `failOpen=false`.
- Allowed/rejected parse: exec returns `{1, r, 0}` / `{0, 0, t}` → `Decision`
  fields mapped correctly; retry-after ≥ 1 preserved from the script on reject.
- Fail-open on exec exception → allowed with `failOpen=true`,
  `circuit.recordFailure()`.
- Circuit breaker: after `circuitFailureThreshold` consecutive exec failures the
  breaker OPENs and subsequent calls fail open **without** hitting exec; after
  `circuitResetMs` one HALF_OPEN probe is admitted; success closes it.
- `normalizeBucket`: null/blank → `global`; illegal chars replaced.
- Snapshot shape: `{enabled, limit, windowSeconds, circuitState}`, no
  `localPassThreshold`.
- The Lua `nowMs` argument passed to exec equals the injected clock value
  (verify via a captor on the mocked exec).

**Integration (`@Tag("docker")`, real Redis, run via `-Dgroups=docker`):**

- **Boundary bound:** with `limit=100`, `window=1s`, drive `limit` requests late
  in window N and `limit` more early in window N+1; assert the number *allowed*
  across the straddle stays close to `limit` (well under the ~2× a fixed window
  would admit) — the core regression proving the fix.
- **Steady state:** at ≤ `limit` requests/window, all allowed; the `(limit+1)`-th
  within a window is rejected with retry-after ≥ 1.
- **Key expiry:** after `2×window` of inactivity, the window keys are gone
  (`TTL`/existence check), so a later request starts fresh.

## Acceptance Criteria

1. The fixed-window Lua is replaced by the weighted sliding-window-counter Lua;
   `tryAcquire` evaluates it on every enabled, circuit-closed request (no local
   fast-path remains).
2. Under a boundary-straddling burst against a real Redis, admitted requests stay
   near `limit` (not ~2×) — proven by the `@Tag("docker")` test.
3. Fail-open, the circuit breaker, `disabled()`, `tryAcquire`/`Decision` shape,
   env vars, and `normalizeBucket` behavior are unchanged.
4. `Snapshot` and `/online/ops` no longer expose `localPassThreshold`; no other
   ops fields change.
5. `OnlineServices` and `OnlinePredictionServer` require no changes (public
   surface preserved).
6. Unit tests cover control flow (circuit/fail-open/parse/disabled/normalize/clock
   arg); the docker test covers sliding-window semantics.
