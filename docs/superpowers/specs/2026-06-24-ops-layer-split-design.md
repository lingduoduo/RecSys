# Operational Layer Split Design

> Archived from the working `SPEC.md`. Splits the two grab-bag `observability/` + `reliability/`
> packages into seven focused, concern-named top-level layers, consolidating duplication on the way.

## 1. Objective

Replace the two grab-bag top-level packages `com.recsys.observability` (5 files) and
`com.recsys.reliability` (15 files) with a set of **focused, concern-named top-level layers**
that each advertise a single operational responsibility - sitting beside `infrastructure/`,
`application/`, `domain/`, etc. The package a class lives in should name its concern
(rate-limiting, load-shedding, metrics) the way `infrastructure/`'s sub-packages name theirs.

While moving the code, **consolidate the genuine duplication** discovered across these files
(multiple token-bucket copies, two circuit-breaker state machines, a shared rolling-window
metrics pattern, duplicated env-readers) so each pattern has a single implementation.

**Behavior must not change.** This is a structural refactor: same runtime behavior, same
metrics, same env/property names, same HTTP responses. `mvn test` must be green at the end.

**Non-goals:** no new features, no renamed env vars / Micrometer meter names / JSON fields,
no rewrites beyond removing duplication, no changes to the excluded `online/flink` or
`training/rulebased` trees (they don't reference these packages).

### Target users
Maintainers of this backend. The win is: one obvious home per operational concern, one
implementation per pattern, and concern-named layers instead of two catch-all buckets.

---

## 2. Commands

```bash
# Compile without tests (fast feedback during the move)
mvn package -DskipTests

# Full verification bar - MUST pass before done
mvn test

# Targeted tests for the touched areas (run frequently while refactoring)
mvn test -Dtest='*RateLimiter*,*LoadShedder*,*CircuitBreaker*,*Metrics*,*Bulkhead*,*FaultInjector*,*AdmissionControl*,*Capacity*,GcEventTracker*,JvmMemoryMonitor*,TraceIdAspect*'

# Sanity grep: no references to the OLD packages should remain anywhere
grep -rn "com\.recsys\.observability\|com\.recsys\.reliability" src --include="*.java"
```

---

## 3. Project Structure

The 20 files spread across **7 new top-level layers** under `com.recsys`, each beside the
existing layers (`api/`, `application/`, `domain/`, `infrastructure/`, `config/`, `exception/`):

```
com.recsys/
├── metrics/        InferenceMetricsService, OnlineServingMetricsService [+ RollingWindowCounter]
├── jvm/            GcEventTracker, JvmMemoryMonitor
├── tracing/        TraceIdAspect
├── ratelimit/      TokenBucket, GatewayRateLimiter, LlmTokenRateLimiter,
│                   ModelRateLimiter, RedisRateLimiter
├── loadshed/       LoadShedder, OnlineLoadShedder, OnlineAdmissionControl,
│                   GracefulShutdownSupport
├── resilience/     RouteCircuitBreaker, WorkerBulkhead, FaultInjector [+ CircuitBreaker]
└── health/         OnlineHealthService, OnlineOpsService, OnlineCapacityService
```

Rationale for groupings:
- `metrics/`, `jvm/`, `tracing/` carve the old `observability/` into its three distinct concerns.
- `GracefulShutdownSupport` lives in `loadshed/` - it exists only to flip `LoadShedder` on SIGTERM.
- `OnlineAdmissionControl` lives in `loadshed/` - it's the Armeria decorator wrapping `OnlineLoadShedder`.
- `health/` holds the online-serving ops surface (health + ops endpoints + capacity sizing), which
  composes metrics + load-shed snapshots.

**Naming note - two `resilience` packages.** This introduces a top-level `com.recsys.resilience`
(request-tier fault tolerance: circuit breaker, bulkhead, fault injection) alongside the existing
`com.recsys.infrastructure.resilience` (cache-protection primitives: bloom/hotkey/single-flight).
FQNs are distinct so the build is unaffected, but readers must not confuse them. (Alternative if the
clash feels risky: name the new layer `faulttolerance` - flagged in section 6.)

### Test structure
Mirror the move under `src/test/java/com/recsys/<layer>/`. Keep each test next to the class it
covers (the 16 existing in-package tests move with their classes; the 26 external caller tests just
get import updates).

### Required non-move edit - Spring component scan (critical)
`api/rest/ModelApplication.java` `@SpringBootApplication(scanBasePackages = {...})` currently lists
`"com.recsys.observability", "com.recsys.reliability"`. Replace those two entries with the new
top-level packages that contain Spring beans - at minimum `metrics`, `loadshed`, `ratelimit`, plus
`jvm`/`tracing` if their classes are annotated. Listing all seven new packages is safe. **If any
bean-bearing package is missed, beans like `LoadShedder` (@Service), `ModelRateLimiter` (@Component),
`InferenceMetricsService` (@Service), `GracefulShutdownSupport` (@Component) silently stop loading.**

### Docs (confirmed in scope)
Update `.claude/CLAUDE.md` Package Map: replace the `observability/` and `reliability/` rows with the
seven new layers, in the same PR, so docs match the code.

---

## 4. Code Style

- Match the surrounding code: same comment density, `final class`, record-based snapshots,
  `LongSupplier` ticker injection for testability - all already idiomatic here; preserve it.
- Package-private constructors used by tests (e.g. `ModelRateLimiter(double,int,int,LongSupplier)`,
  `RedisRateLimiter(...)`) must stay package-private **and** keep their tests in the same package.
- No wildcard imports. Update `package` declarations and imports precisely; fix the stale path
  comments at the top of files (e.g. `WorkerBulkhead.java` line 1 says `streaming/WorkerBulkhead.java`).
- Keep public env-var names, Micrometer meter names, gauge descriptions, and JSON field names byte-for-byte.

### Consolidation tasks (the "combine code together" goal)

Do these **behind the green test suite**, smallest/safest first. Each must be behavior-preserving.

| ID | Change | Risk | Required? |
|----|--------|------|-----------|
| C1 | `ratelimit.ModelRateLimiter`: delete its private inner `TokenBucket` + private `Decision`; reuse `ratelimit.TokenBucket` and `TokenBucket.Decision`. (Per-user behavior identical - same refill math.) | Low | **Yes** |
| C2 | `ratelimit.RedisRateLimiter`: replace private `readIntEnv`/`readLongEnv` with the existing `EnvConfig`/`EnvVars` readers. | Low | **Yes** |
| C3 | Extract a shared `resilience.CircuitBreaker` (CLOSED/OPEN/HALF_OPEN + consecutive-failure + cooldown + single-probe CAS). Have `RouteCircuitBreaker` and `RedisRateLimiter`'s embedded circuit delegate to it. | Med | Yes, **iff** Redis fail-open semantics (allow-on-exception, probe-on-half-open) are provably unchanged; otherwise extract for `RouteCircuitBreaker` only and leave a `// see CircuitBreaker` note on Redis. |
| C4 | Extract a shared `metrics.RollingWindowCounter` (deque + running totals `evict()`) and the bounded sub-metric map (`MAX_VARIANTS`/`MAX_STRATEGIES` cap). Both metrics services use it; their public `Snapshot` records stay unchanged. | Med | Yes, if `Snapshot` outputs are identical under test. |
| C5 | Load shedders: **leave both implementations as-is** (`loadshed.LoadShedder` = Spring/Micrometer/Semaphore; `loadshed.OnlineLoadShedder` = plain/CAS/EnvConfig). Co-locate only - **do not merge** (decided: behavior risk outweighs the dedup). | - | **No (co-locate only)** |

If any consolidation can't preserve behavior cleanly, **prefer the plain move** for that file and
record why in the PR description. Reorg correctness outranks maximal dedup.

> **Outcome (as shipped, PR #150):** C1–C3 done; C3 extracted `resilience.CircuitBreaker` used by both
> `RouteCircuitBreaker` and `RedisRateLimiter`. C4 deferred (the two metrics windows diverge enough that
> a shared generic adds more code/risk than it removes). C5 co-located only, as decided.

---

## 5. Testing Strategy

- **Bar: `mvn test` fully green.** No skipped tests, no quarantine.
- Move-only changes are validated by the existing suite compiling and passing after import updates.
- Each consolidation (C1-C4) is gated on its existing tests:
  - C1 -> `ModelRateLimiter` tests still pass (per-user limiting, LRU cap, disabled mode).
  - C2 -> `RedisRateLimiter` tests still pass (env defaults, circuit, fail-open).
  - C3 -> both `RouteCircuitBreaker` and `RedisRateLimiter` circuit tests pass; if either lacks a
    half-open/probe test, add one **before** extracting (characterization test).
  - C4 -> both metrics `Snapshot` tests pass (window eviction, percentiles, bounded maps).
- After the move, the sanity grep in section 2 must return **zero** hits for the old package names.
- Run the full `mvn test` once more after all consolidations.
- Load tests stay opt-in/excluded (unchanged).

---

## 6. Boundaries

**Always:**
- Keep behavior, env var names, meter names, gauge descriptions, HTTP status/JSON shapes identical.
- Update `ModelApplication.scanBasePackages` in the same change that renames the packages.
- Keep test classes in the same package as the class-under-test when they use package-private members.
- Verify with `mvn test` before declaring done.
- Update `.claude/CLAUDE.md`'s Package Map in the same PR (confirmed in scope).
- Do the work on the current feature branch and open a **PR** for review - never merge to `main`
  directly (per repo workflow).

**Ask first:**
- Whether the new top-level `resilience/` should instead be named `faulttolerance/` to avoid visual
  overlap with `infrastructure/resilience/` (default: keep `resilience/`).
- Any consolidation (C3/C4) that turns out to need a behavior change to land - fall back to a plain move.

**Never:**
- Change the excluded `online/flink` or `training/rulebased` trees.
- Rename or drop public env vars, Micrometer meters, or JSON response fields.
- Introduce new dependencies or frameworks.
- Merge the two load shedders (C5 is co-locate-only).
- Leave a partially-migrated state (some refs on old package, some on new).

---

## Appendix - Move map (old -> new)

| File | New package |
|------|-------------|
| observability/InferenceMetricsService | metrics |
| observability/OnlineServingMetricsService | metrics |
| observability/GcEventTracker | jvm |
| observability/JvmMemoryMonitor | jvm |
| observability/TraceIdAspect | tracing |
| reliability/TokenBucket | ratelimit |
| reliability/GatewayRateLimiter | ratelimit |
| reliability/LlmTokenRateLimiter | ratelimit |
| reliability/ModelRateLimiter | ratelimit |
| reliability/RedisRateLimiter | ratelimit |
| reliability/LoadShedder | loadshed |
| reliability/OnlineLoadShedder | loadshed |
| reliability/OnlineAdmissionControl | loadshed |
| reliability/GracefulShutdownSupport | loadshed |
| reliability/RouteCircuitBreaker | resilience |
| reliability/WorkerBulkhead | resilience |
| reliability/FaultInjector | resilience |
| reliability/OnlineHealthService | health |
| reliability/OnlineOpsService | health |
| reliability/OnlineCapacityService | health |
