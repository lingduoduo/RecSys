# Task 7 report: atomic Top-K publication

## Result

- Replaced the independent canonical Top-K, hot-movies, and trend writes with one Redis Lua invocation.
- The script uses the shared `{window}` Redis Cluster hash tag for all five keys and atomically rebuilds both sorted sets, stores trend and `eventTimeMillis|eventId` metadata, and records lineage with matching TTLs.
- Versions are ordered by numeric event time and then lexicographic event ID. Only a strictly greater tuple applies; identical replay and stale/equal-lower IDs are no-ops.
- `TopKSnapshot` now carries a deterministic version ID. Flink-generated window snapshots use `window-<windowEnd>`.
- Readers prefer `topk:{<window>}:value` and retain fallback to legacy `topk:<window>` data.

## Stateful topology and savepoints

The stateful UIDs and max parallelism are unchanged: `topk-partial-v1` and `topk-final-v1` retain their prior state contracts. The atomic sink keeps `redis-topk-sink-v1`. The retired `redis-trend-feature-sink-v1` remains attached as a state-only no-op terminal, avoiding a savepoint topology removal while eliminating its external Redis write.

## TDD evidence

- RED: `mvn -Pstreaming-flink test -Dtest=OnlineFeatureStreamingJobTest` failed test compilation because the snapshot event ID and atomic `apply` API did not exist.
- GREEN: focused Redis/Flink and reader suites pass after implementation (see final verification output in the task handoff).

## Key contract

- `topk:{last_hour}:value`
- `feature:{last_hour}:hot_movies`
- `feature:{last_hour}:trend`
- `topk:{last_hour}:version`
- `lineage:{last_hour}:event:<eventId>`

All keys share the `last_hour` hash slot tag. Top-K lineage is intentionally window-scoped to satisfy Redis Cluster script rules; the existing per-user lineage contract (`lineage:event:<eventId>`) is unchanged.
