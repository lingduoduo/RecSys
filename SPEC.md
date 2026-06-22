# Spec: Simplify `model/service` — dedupe Redis pool + normalization, drop dead overloads

## Objective

Remove two pockets of duplication in `com.recsys.model.service` (28 files) with
**zero behavior change**:

- **A. Shared lazy Jedis pool** — `LoginTokenService` and `SubmitTokenService`
  carry byte-identical lazy-pool plumbing (`volatile Pool<Jedis>`, a `Supplier`,
  the double-checked-locking `jedis()`, and `@PreDestroy close()`). Extract it
  into one `LazyJedisPool` helper (composition).
- **C. Normalization idiom** — the `value == null || value.isBlank() ? default :
  value.trim()` one-liner is copy-pasted across 5 files. Route the matching
  copies through one shared `Strings.orDefault(value, default)` helper.

> **Dropped:** removing the unused `InferenceMetricsService.recordSuccess(long)` /
> `recordFailure(long)` overloads — they are a coherent, tested no-variant API and
> removing them would rewrite 13 test call sites. Left in place.

### Who benefits
`model/service` maintainers — one lazy-pool implementation, one normalization
primitive, and less unused surface in the metrics API.

### Success looks like
- Both token services share `LazyJedisPool`; neither declares its own pool field,
  `jedis()`, or `close()` body.
- The normalization idiom exists once; the 6 matching call sites use it.
- The two dead overloads are gone (if B is kept).
- `mvn test` stays green.

## Tech Stack

- Java 17, Spring Boot, Jedis, Micrometer; JUnit 5 + Mockito + AssertJ. Maven.

## Commands

```bash
mvn package -DskipTests
mvn test -Dtest='SubmitTokenServiceTest,InferenceMetricsServiceTest,ModelRateLimiterTest,UserTowerInferenceServiceTest,ModelRuntimeProviderTest,ABTestServiceTest'
mvn test
```

## Scope

### A. `LazyJedisPool` (recommended)

New package-private helper:

```java
final class LazyJedisPool implements AutoCloseable {
    private final Supplier<Pool<Jedis>> poolFactory;
    private volatile Pool<Jedis> pool;

    LazyJedisPool(Supplier<Pool<Jedis>> poolFactory) { this.poolFactory = poolFactory; }

    Jedis resource() {                       // lazy double-checked init, then borrow
        Pool<Jedis> current = pool;
        if (current == null) {
            synchronized (this) {
                current = pool;
                if (current == null) { current = poolFactory.get(); pool = current; }
            }
        }
        return current.getResource();
    }

    @Override public void close() {          // idempotent pool shutdown
        Pool<Jedis> current = pool;
        if (current != null) current.close();
    }
}
```

Both services hold `private final LazyJedisPool jedisPool;` (built from the same
`Supplier` they already receive), call `try (Jedis j = jedisPool.resource())`, and
keep a thin `@PreDestroy public void close() { jedisPool.close(); }`. The
per-service `redisKey(...)` (hardcoded `"login:"` vs configurable prefix) stays
exactly as-is. Constructor signatures are unchanged, so `SubmitTokenServiceTest`
(and the Spring beans) need no edits.

### C. `Strings.orDefault` normalization

New package-private helper:

```java
final class Strings {
    private Strings() {}
    /** Trimmed value, or {@code defaultValue} when null/blank. */
    static String orDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
```

Route these **exact** matches through it (all trim the value, blank → default):

| Site | Becomes |
|---|---|
| `ModelVariants.normalizeOrDefault` body | `Strings.orDefault(variant, DEFAULT)` |
| `ModelRateLimiter.normalizeUserId` body | `Strings.orDefault(userId, "_anonymous")` |
| `InferenceMetricsService.normalizeVariant` body | `Strings.orDefault(variant, "unknown")` |
| `UserTowerInferenceService` ctor (modelFile) | `Strings.orDefault(modelFile, DEFAULT_MODEL_FILE)` |
| `ModelRuntimeProvider` ctor (modelFile) | `Strings.orDefault(modelFile, "dssm_model.onnx")` |
| `ModelRuntimeProvider` ctor (redisItemEmbeddingPrefix) | `Strings.orDefault(redisItemEmbeddingPrefix, "i2vEmb")` |

The named wrappers (`normalizeOrDefault`, `normalizeUserId`, `normalizeVariant`)
stay as one-line delegators so their call sites and intent names are unchanged.

**Deliberately NOT converted** (different behavior — must stay inline):
- `ModelRuntimeProvider:82` `itemEmbeddingsSource == null ? "classpath" : …trim()`
  — null-only check (blank is **not** mapped to default).
- `ABTestService:54` `layerName == null || isBlank ? s.layerName() : layerName`
  — value is **not** trimmed and the default is dynamic.
- `ABTestService:55` — a boolean guard, not a normalization.

## Project Structure (after)

```
model/service/
  LazyJedisPool.java          NEW — shared lazy Jedis pool (A)
  Strings.java                NEW — orDefault normalization (C)
  LoginTokenService.java      uses LazyJedisPool
  SubmitTokenService.java     uses LazyJedisPool
  InferenceMetricsService.java  normalizeVariant delegates (C)
  ModelVariants.java / ModelRateLimiter.java / UserTowerInferenceService.java /
  ModelRuntimeProvider.java   normalization delegates to Strings (C)
  (24 other files unchanged)
```

## Code Style

- Both new helpers are package-private `final` with private constructors (`Strings`)
  / single field (`LazyJedisPool`); `LazyJedisPool` is `AutoCloseable`.
- Preserve every Redis key prefix, TTL, default string, and metrics tag exactly.
- Keep the domain-named wrapper methods; only their bodies collapse to a delegate.

## Testing Strategy

JUnit 5 + Mockito + AssertJ. The existing tests are the safety net.

- **A:** no test changes — constructor signatures preserved; `SubmitTokenServiceTest`
  passes unchanged.
- **C:** no behavior change → existing tests for `ModelRateLimiter`,
  `InferenceMetricsService`, `UserTowerInferenceService`, `ModelRuntimeProvider`,
  `ABTestService` stay green unchanged.

Verify: the named subset above, then full `mvn test`.

## Boundaries

- **Always:** preserve Redis prefixes/TTLs, default strings, metrics tags, and
  pool lifecycle; preserve constructor signatures; run the suite.
- **Ask first:** changing any default value, key prefix, or metrics behavior;
  converting the explicitly-excluded normalization sites; touching the other ~22
  files.
- **Never:** change runtime behavior; delete a test that covers live behavior to
  make the build pass.

## Success Criteria

1. `LazyJedisPool` exists; both token services use it and declare no pool field /
   `jedis()` / `close()` body of their own.
2. `Strings.orDefault` exists; the 6 matching sites delegate to it; the 3 excluded
   sites are untouched.
3. `mvn test` green; no file outside `model/service/` changed.

## Open Questions

1. **Helper names.** `LazyJedisPool` and `Strings` (package-private). Alternatives:
   `JedisPoolHolder`, `Text`. Default as written.
