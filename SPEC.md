# Spec: Dedupe `online/ops` env-readers via a shared `EnvConfig`

## Objective

Three `online/ops` services privately re-declare the same "read an environment
variable, parse it, fall back to a default" helper —
`OnlineCapacityService.readLongEnv`, `OnlineLoadShedder.readIntEnv` +
`readDoubleEnv`, and `OnlineServingMetricsService.readIntEnv`. This spec extracts
one shared `com.recsys.infrastructure.EnvConfig` (`readInt`/`readLong`/`readDouble`)
and points the three `online/ops` files at it — **with zero behavior change.**

The helper lives in `infrastructure` (not `online/ops`) so it doesn't become yet
another local copy of a codebase-wide pattern; other files can adopt it later.
Migrating them is **out of scope** for this change.

### Who benefits
Maintainers of `online/ops` (and, going forward, anyone reading env vars) — one
correct parse-with-default implementation instead of N copies.

### Success looks like
- `EnvConfig` exists with three static readers.
- The 4 duplicated private env-readers in the 3 `online/ops` files are gone,
  replaced by `EnvConfig.read*` calls.
- `mvn test` stays green; defaults and parsing behave exactly as before.

## Tech Stack

- Java 17; JUnit 5 + AssertJ. Build: Maven.

## Commands

```bash
mvn package -DskipTests
mvn test -Dtest='OnlineLoadShedderTest,OnlineCapacityServiceTest,OnlineServingMetricsServiceTest,EnvConfigTest'
mvn test
```

## Scope — one consolidation (as selected)

### New `com.recsys.infrastructure.EnvConfig`

```java
public final class EnvConfig {
    private EnvConfig() {}

    public static int readInt(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public static long readLong(String name, long defaultValue) { /* same shape, Long.parseLong */ }
    public static double readDouble(String name, double defaultValue) { /* same shape, Double.parseDouble */ }
}
```

This reproduces the existing helpers' behavior byte-for-byte: `null`/blank →
default; trimmed parse; `NumberFormatException` → default.

### Migrate the 3 `online/ops` files

| File | Removes | Call becomes |
|---|---|---|
| `OnlineCapacityService` | `readLongEnv` | `EnvConfig.readLong("ONLINE_TARGET_DAU", …)` etc. |
| `OnlineLoadShedder` | `readIntEnv`, `readDoubleEnv` | `EnvConfig.readInt(…)`, `EnvConfig.readDouble(…)` |
| `OnlineServingMetricsService` | `readIntEnv` | `EnvConfig.readInt("ONLINE_METRICS_WINDOW_SECONDS", …)` |

Each gains `import com.recsys.infrastructure.EnvConfig;`. No other lines change.

### Explicitly out of scope
- The same pattern in ~10 other files (`RecSysServer`, `OnlinePredictionServer`,
  `RecallConfig`, `RedisRateLimiter`, `OnlineFeatureStore`, `AsyncEventPublisher`,
  `LocalEmbeddingCache`, `RedisEmbeddingStore`, `ShardedTopKStore`) — left as-is;
  they can adopt `EnvConfig` in future changes.
- Everything else in `online/ops` (`FaultInjector`, `WorkerBulkhead`,
  `OnlineAdmissionControl`, the two HTTP handlers, all `Snapshot` records) —
  distinct and cohesive, untouched.

## Project Structure (after)

```
infrastructure/EnvConfig.java              NEW — readInt/readLong/readDouble
online/ops/OnlineCapacityService.java      uses EnvConfig.readLong
online/ops/OnlineLoadShedder.java          uses EnvConfig.readInt/readDouble
online/ops/OnlineServingMetricsService.java uses EnvConfig.readInt
online/ops/* (FaultInjector, WorkerBulkhead, OnlineAdmissionControl,
             OnlineHealthService, OnlineOpsService)   (unchanged)
```

## Code Style

- `EnvConfig` is `public final` with a private constructor; methods `public static`.
- Method names `readInt`/`readLong`/`readDouble` (shorter than the old
  `read*Env`; the type name supplies the "env" context).
- Identical semantics to the replaced helpers — no new validation, no logging.

## Testing Strategy

JUnit 5 + AssertJ. The existing service tests (`OnlineLoadShedderTest`,
`OnlineCapacityServiceTest`, `OnlineServingMetricsServiceTest`) exercise the
default path (env unset) via the no-arg constructors and remain the safety net —
they must stay green unchanged.

Add a small `EnvConfigTest` covering the default path (the reliably testable one
without setting process env): each reader returns the default for an absent var.
This is more coverage than the original private helpers had.

## Boundaries

- **Always:** preserve env-var names, default values, and parse/fallback behavior
  exactly; run the ops tests + full suite.
- **Ask first:** migrating env-readers outside `online/ops`; changing any default
  value or env-var name; adding validation/logging to the readers.
- **Never:** change runtime behavior; delete a test to make the build pass.

## Success Criteria

1. `infrastructure/EnvConfig.java` exists with `readInt`/`readLong`/`readDouble`.
2. `OnlineCapacityService`, `OnlineLoadShedder`, `OnlineServingMetricsService` no
   longer declare private env-readers; they call `EnvConfig`.
3. `git diff` shows no change to env-var names, defaults, or parse semantics.
4. `mvn test` green; no file outside `online/ops/` + the new `EnvConfig` changed.

## Open Questions

1. **Name.** `EnvConfig` chosen (matches the `*Config` names in `infrastructure`).
   Alternative: `Env` (`Env.readInt`). Default: `EnvConfig`.
