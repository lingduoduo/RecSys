# Rate Limiting in Recsys-Backend-Service

An investigation of the five rate limiters in the system: one shared token-bucket
primitive, three per-instance limiters that guard the gateway, the model service,
and the LLM proxy, and one global cluster-wide ceiling backed by Redis. The unifying
theme is **fail-open** — a disabled limiter admits traffic rather than dropping it,
and an enabled Redis limiter with its emergency bucket configured degrades to a
bounded local ceiling rather than an unlimited one — and the unifying question for
each is *what is limited, by what key, and is the ceiling per-instance or global?*

## The big picture

| Limiter | What it limits | Key | Scope | Cost/call | Disabled default |
|---|---|---|---|---|---|
| `TokenBucket` | (primitive) | — | — | — | — |
| `GatewayRateLimiter` | Gateway requests | `(route, principal)` | per-instance | 1 | `RPS=0`/`BURST=0` |
| `ModelRateLimiter` | Model inference | served `userId` | per-instance | 1 | `rps=0`/`burst=0` |
| `LlmTokenRateLimiter` | LLM **tokens** | one bucket | per-instance | `max_tokens` | `TPS=0`/`BURST=0` |
| `RedisRateLimiter` | Online serving QPS | one global bucket | **global** | 1 | `QPS=0` |

Two facts shape everything below:

- **Four of five are per-instance token buckets** built on the same primitive, so
  their effective ceiling is `per-instance-limit × replica count` — they scale up as
  the fleet does.
- **Only an enabled `RedisRateLimiter` is a true global ceiling** (state in Redis,
  consulted on every enabled request), and it is the one that adds a dependency. Its
  fail-open path is bounded only when the emergency bucket is also configured.

Every limiter **fails open**: setting a limit to `0` returns an unlimited decision.
The Redis emergency bucket exists only when a Redis executor is configured, QPS is
positive, `ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED` is true, and the emergency rate and
burst are both positive. With that combination, an open breaker or Redis error falls
back to the conservative per-replica bucket; otherwise the failure path is unlimited.
With the global QPS default of `0`, `RedisRateLimiter` is disabled and does not create
an emergency bucket.
Rate limiting is the *first* gate in the overload stack (rate limit →
admission → bulkhead — see [Fault Tolerance](18_Fault_Tolerance.md#gate-ordering)).

## 1. `TokenBucket` — the shared primitive

[`TokenBucket`](../../src/main/java/com/recsys/ratelimit/TokenBucket.java) is a lazy-refill
token bucket: `refillPerNano = ratePerSecond / 1e9`, capacity = `burst` (starts
full), and `refill()` adds `elapsed × refillPerNano` capped at `burst` with a
monotonic guard so a non-advancing clock is a no-op. `tryAcquire(needed)` returns a
`Decision(allowed, limit, remaining, retryAfter)` — subtracting on success, or
computing `retryAfter = ceil((needed − tokens) / refillPerNano)` on failure. It is
**`synchronized`** per bucket (not lock-free), and takes an injectable
`LongSupplier` clock (`System::nanoTime` in prod, a fake ticker in tests). The
gateway, model, and LLM limiters are all thin wrappers around it; `RedisRateLimiter`
does not use it (it computes in Lua).

## 2. Gateway rate limiting — per `(route, principal)`

[`GatewayRateLimiter`](../../src/main/java/com/recsys/ratelimit/GatewayRateLimiter.java)
holds one `TokenBucket` per `route|principal` in a bounded Caffeine cache
(`maximumSize = GATEWAY_RL_MAX_PRINCIPALS`, default 100000, `expireAfterAccess` 60
min) so one noisy caller can't exhaust another's budget and a flood of distinct
identities can't exhaust memory. The principal is derived from the authenticated
identity (`GatewayPrincipal.rateLimitKey()`: Cognito `sub`, a hashed API-key id, or
`anonymous`). It refills at `GATEWAY_RATE_LIMIT_RPS` with a `GATEWAY_RATE_LIMIT_BURST`
burst (both default `0` → disabled), with per-route overrides
(`GATEWAY_RATE_LIMIT_<ROUTE>_RPS`). On limit the gateway returns `429` with
`Retry-After` and `x-ratelimit-*` headers. Full mechanics are in the
[API Gateway investigation](09_API_Gateway.md#4-rate-limiting).

## 3. Model rate limiting — per-user

[`ModelRateLimiter`](../../src/main/java/com/recsys/ratelimit/ModelRateLimiter.java) caps
`POST /api/v1/recommend` **per served user** so one high-traffic user can't
monopolize scarce ONNX inference slots. It keeps one `TokenBucket` per `userId` in an
access-ordered LRU map (`recsys.model.rate-limit.max-users`, default 10000; null/blank
share an `_anonymous` bucket), and — importantly — it is keyed on the *served*
`request.getUserId()`, not the calling principal, and runs **before** the
load-shedder semaphore in
[`RecommendationController`](../../src/main/java/com/recsys/api/rest/RecommendationController.java)
so a single user can't burn shared concurrency before being limited.

```bash
# Enable: 5 req/s per user, burst 10
RECSYS_MODEL_RATE_LIMIT_RPS=5.0 RECSYS_MODEL_RATE_LIMIT_BURST=10 \
  sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run

# Rapid-fire one user → 200 … then 429
for i in $(seq 1 15); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/v1/recommend \
    -H "Content-Type: application/json" -d '{"userId":"1","k":1}'
done
```

On deny it throws `RateLimitExceededException`, which
[`GlobalExceptionHandler`](../../src/main/java/com/recsys/exception/GlobalExceptionHandler.java)
maps to `429` + `Retry-After` with body
`{"error":"request rate limit exceeded — retry after Ns","violations":[]}`.

| Property | Default | Purpose |
|---|---:|---|
| `recsys.model.rate-limit.rps` | `0.0` | Per-user req/s (`0` = disabled) |
| `recsys.model.rate-limit.burst` | `0` | Burst capacity |
| `recsys.model.rate-limit.max-users` | `10000` | Max tracked users (LRU eviction) |

## 4. LLM token-budget limiting

[`LlmTokenRateLimiter`](../../src/main/java/com/recsys/ratelimit/LlmTokenRateLimiter.java)
is the odd one out: it limits **tokens, not requests**. A single bucket refills at
`LLM_TOKEN_RATE_LIMIT_TPS` tokens/sec with an `LLM_TOKEN_RATE_LIMIT_BURST` capacity,
and each call costs the request's `max_tokens` (parsed from the body, with a default
estimate fallback) rather than `1` — so a few large-context requests are throttled
the same as many small ones, protecting a shared downstream token quota. It is a
pre-check in
[`LlmProxyService`](../../src/main/java/com/recsys/application/gateway/LlmProxyService.java)
after the cache lookup and before the circuit breaker; on limit it returns `429` with
`Retry-After` / `x-ratelimit-*` and body `{"error":"<route> token budget exhausted …"}`.
It sits inside the gateway's LLM proxy (SSE, caching, circuit breaker), whose streaming
behavior is documented in [SSE Streaming](16_SSE_Streaming.md).

## 5. The global ceiling — `RedisRateLimiter`

[`RedisRateLimiter`](../../src/main/java/com/recsys/ratelimit/RedisRateLimiter.java) is the
**only cluster-wide limit**: a weighted **sliding-window counter** implemented as an
inline Lua script run on Redis, so every online-serving instance shares one ceiling
regardless of replica count. Per fixed window it computes a rolling estimate
`prev × weight + cur` (where `weight` is the fraction of the window remaining) and
rejects when it would exceed `ONLINE_REDIS_RATE_LIMIT_QPS` — bounding the rolling
rate to ≈1× the limit rather than the ~2× a naive fixed window allows. It
deliberately has **no per-instance local fast-path** (that would leak
`replicas × fraction × limit` before the global limit engaged); every request
consults Redis.

Because it adds a Redis dependency on the hot path, it is wrapped in an embedded
[`CircuitBreaker`](../../src/main/java/com/recsys/resilience/CircuitBreaker.java) (5
consecutive failures → open for 30 s). An exception, malformed reply, or open circuit
uses the conservative per-replica emergency token bucket and marks the decision
`failOpen=true`; emergency exhaustion still returns `429` with a positive
`Retry-After`. The emergency bucket is instantiated only when a Redis executor is
configured, `ONLINE_REDIS_RATE_LIMIT_QPS > 0`,
`ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED=true`, and the emergency rate and burst are both
positive. Operators can set the flag to `false` or either value to zero to restore
unlimited fail-open behavior for rollback. The limiter is consumed by the online
serving path (7010) after local admission, and its state is exposed via `/online/ops`.
The design and the NTP-synced-clock caveat (the ≈1× bound assumes synced wall
clocks) are covered in the [Fault Tolerance
investigation](18_Fault_Tolerance.md#rate-limiters--bounded-fail-open-with-an-embedded-breaker);
the design spec is
[redis-rate-limiter-sliding-window](../superpowers/specs/2026-07-20-redis-rate-limiter-sliding-window-design.md).

## 6. How they layer

- **Per-instance vs global.** The gateway, model, and LLM limiters are in-process, so
  their aggregate ceiling grows with the fleet; an enabled `RedisRateLimiter` is the
  single fixed ceiling that holds regardless of replica count. Use the per-instance
  limiters for fairness (no caller/user/context starves another) and the global one
  for a hard capacity cap.
- **Rate limit first.** In the overload stack the cheapest, most-global gate runs
  first: **rate limit → admission (concurrency) → bulkhead**, pinned by the
  `@Tag("load")` `OverloadGateOrderingCharacterizationTest` and documented in
  [Fault Tolerance](18_Fault_Tolerance.md#gate-ordering) and
  [overload-protection.md](../runbooks/overload-protection.md). Note the two hosts
  order their local gates differently: the model controller runs the per-user limiter
  *then* the load-shedder; the online path runs admission *then* the Redis global
  limiter.
- **Fail-open everywhere.** Every limiter defaults to disabled (`0`) and admits when
  off. When its Redis executor, global QPS limit, emergency flag, rate, and burst are
  all configured, the Redis limiter bounds a breaker-open or error path locally;
  otherwise that failure path is unlimited.

## 7. Testing

- **Per-instance limiters** — `GatewayRateLimiterTest` (burst-then-limit, per-route
  override, per-principal isolation), `ModelRateLimiterTest` (per-user buckets, LRU
  eviction, `_anonymous` sharing, refill timing), `LlmTokenRateLimiterTest`
  (burst-then-block, a large request blocked for insufficient tokens). The
  `TokenBucket` primitive has no test of its own — it's exercised transitively.
- **The global limiter** — `RedisRateLimiterTest` (mocked Redis: bounded fail-open on
  error via the emergency bucket, the CLOSED→OPEN→HALF_OPEN→CLOSED breaker lifecycle
  under generation-bound permits, no local pass-threshold) and
  `RedisRateLimiterSlidingWindowIntegrationTest` (`@Tag("docker")`, real Redis + an
  injected logical clock: proves the boundary burst stays ≈1× not ≈2×, and window
  keys self-expire).
- **Ordering** — `OverloadGateOrderingCharacterizationTest` (`@Tag("load")`) pins the
  rate-limit → admission → bulkhead knee.

## Sharp edges — notes

1. **Per-instance limits move with the fleet.** Four of five ceilings multiply by
   replica count — the only hard, replica-independent cap is an enabled
   `RedisRateLimiter`. A "5 rps per user" model limit is really "5 × replicas"
   cluster-wide.
2. **The LLM limiter counts tokens, not requests.** Sizing `LLM_TOKEN_RATE_LIMIT_TPS`
   like a request rate will throttle far more aggressively than expected, because one
   large-context call can cost hundreds of tokens.
3. **The global limiter trusts the clock.** Its ≈1× bound assumes NTP-synced wall
   clocks across instances; badly skewed clocks widen the effective window.
4. **Fail-open is a deliberate availability choice.** When a Redis executor, global
   QPS limit, emergency flag, rate, and burst are all configured, a Redis outage or
   breaker trip switches from the global ceiling to the per-replica emergency bucket.
   Otherwise the failure path is unlimited. The emergency ceiling still grows with
   replica count; watch the breaker and emergency counters in `/online/ops`.
5. **Everything is off by default.** Every limiter ships disabled (`0`); a deployment
   that never sets the env vars has no rate limiting at all — the limits are an opt-in
   operational control, not a built-in default.
