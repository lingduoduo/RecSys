# Spec: Cache Memory Safety (bounded maps + eviction off the hot path)

## Objective
Stop three unbounded/on-hot-path growth patterns the audit flagged, eliminating heap-bloat/OOM risk under cache-penetration or high-cardinality load, and removing an O(N) scan from the online read path. Behavior-preserving. Leverages the Caffeine dependency added in the hot-path optimization.

## Scope

### A. Bound `LocalEmbeddingCache.nullSentinels`
File: `src/main/java/com/recsys/infrastructure/cache/LocalEmbeddingCache.java` (line 65: `ConcurrentHashMap<Integer, Long> nullSentinels`).
Absent-id sentinels are added with a 30s logical TTL but only checked on access — under random non-existent-id traffic the map grows unbounded. Replace with a Caffeine cache `Cache<Integer, Boolean>` built with `expireAfterWrite(NULL_SENTINEL_TTL_MS)` and a `maximumSize` cap. Membership check = `getIfPresent != null`; `put` = `put(id, Boolean.TRUE)`; `remove` stays.
- Preserve: 30s sentinel semantics, the `remove` on write/set, and the existing penetration tests.

### B. Schedule `HotKeyDetector.evictIdle`
File: `src/main/java/com/recsys/infrastructure/resilience/HotKeyDetector.java` (line 31-32 maps; `evictIdle()` at line 97).
`evictIdle()` exists but relies on an external caller; if unscheduled the two `ConcurrentHashMap`s leak idle keys. Add an opt-in scheduled sweep (a small shared daemon `ScheduledExecutorService`, cadence = `windowMs`, env-gated on/off) that calls `evictIdle()`, plus a hard size cap as a backstop. Provide a `close()` to stop the sweep.
- Preserve: hot-key detection thresholds and `evictIdle` logic; scheduling is additive.

### C. Move `OnlineFeatureStore` eviction off the read path
File: `src/main/java/com/recsys/infrastructure/store/OnlineFeatureStore.java` (`evictIfNeeded` at line 229; called inline at lines 130, 163; `removeIf` O(N) at line 235).
Eviction is rate-limited but still runs an O(N) `removeIf` inline on the fetch path, adding jitter. Move it to a background scheduled task (daemon, cadence ≈ the existing rate-limit interval); the read path no longer calls `evictIfNeeded`. Add `close()` to stop it.
- Preserve: staleness semantics (entries past `staleExpiresAtMs` are removed); only the *when/where* of the sweep changes.

## Out of Scope
- `MultiLevelEmbeddingCache` L1 arbitrary-vs-LRU eviction (low impact; separate).
- Changing the embedding cache itself (already Caffeine-backed).

## Testing
- A.: penetration test — inserting many absent ids does not grow the sentinel structure beyond the cap; sentinel still suppresses a backing call within TTL and expires after it (use an injectable clock/ticker).
- B.: test `evictIdle` still prunes; test the scheduled sweep invokes it (inject a deterministic executor / call the sweep directly); size cap holds.
- C.: test that a stale entry is removed by the background sweep (run the task directly with an injected clock) and that the read path no longer triggers the O(N) scan (e.g., reads do not change eviction-scan counters).
- `mvn clean test` green; no leaked non-daemon threads.

## Risks
- New background threads — must be daemon, shared where possible, and stopped via `close()` so they never block JVM shutdown or leak across tests.
- Moving eviction off the read path slightly delays reclamation (bounded by sweep cadence) — acceptable and documented.

## Success
- `nullSentinels` and `HotKeyDetector` maps are bounded and self-pruning; `OnlineFeatureStore` reads do no O(N) eviction; existing tests green.
