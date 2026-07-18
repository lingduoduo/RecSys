# Kafka/Flink final-review fix report

Status: DONE_WITH_CONCERNS

## Implemented

- Locked the normal artifact to `movie_events_v2` / `kafka-movie-events-v2` and added explicit legacy bridge mode using `kafka-movie-events-bridge-v1` and its own default group.
- Required nonblank checkpoint storage for all Kafka modes while retaining checkpoint-optional local file replay.
- Replaced per-event full `MapState` scans with Flink per-entry TTL (`OnCreateAndWrite`, `NeverReturnExpired`, RocksDB compaction cleanup).
- Unified producer and Flink user IDs on positive Java `int`, including exact exponent-number handling and rejection of fractions/overflow.
- Pinned `topk-final-v1` max parallelism and strengthened the load guard with processed-rate, bounded-lag, failed-checkpoint, checkpoint-age, and duration assertions.
- Added transport-neutral Kafka failure logs and a backward-compatible `deliveryFailures` snapshot field.
- Updated producer scripts, README, and cutover runbook for bridge replay/reconciliation and bridge-savepoint-to-v2 restore.

## Verification evidence

- `sh streaming/online-serving/scripts/test_produce_movie_events.sh`: PASS.
- `mvn -Pstreaming-flink test-compile -DskipTests`: BUILD SUCCESS (198 test sources).
- Initial focused run: 40 tests, 0 failures, 0 errors, 3 Docker-dependent skips, BUILD SUCCESS.
- A later focused rerun was blocked by the local JDK's Mockito inline-agent self-attachment failure; this is an environment failure, not an assertion failure.
- `mvn test`: progressed through the default suite with passing tests, but the captured invocation did not return a final Maven summary in the tool window and is not claimed as complete.
- Docker/Testcontainers could not connect to the local Docker socket, so Kafka/Flink integration, restore/rescale, and 50k/s load execution remain unverified locally.

## Remaining concerns

- Run the Docker-tagged Kafka/Flink integration and load gates in CI with Docker and durable checkpoint storage.
- Run savepoint bridge-to-v2 restore/rescale coverage in a Flink environment; local compilation covers the graph contracts, not a real restore.

## Follow-up safety review

- Bridge mode now replaces every production Redis terminal with a no-op terminal under the same sink UID; stateful processing operators, state descriptors, max parallelism, and restore identities remain unchanged.
- Recent-history `ListState`, user-embedding `ValueState`, and session `ValueState` now use the same per-entry/write-refresh TTL policy as dedup state, including `NeverReturnExpired` and RocksDB compaction cleanup.
- Kafka modes accept shared durable checkpoint schemes only. Relative paths, `/tmp`, and `file:` are rejected unless `--allow-local-checkpoint-storage true` is explicitly supplied for local/Docker tests.
- Focused bridge/config/TTL graph test: 26 tests, 0 failures, 0 errors, 3 Docker skips, BUILD SUCCESS.
- Streaming `test-compile` and default package build both completed with BUILD SUCCESS after the follow-up.
- A sandboxed full-suite attempt failed when Armeria test servers were denied permission to bind local ports. The approved outside-sandbox retry progressed with passing tests, but the execution capture ended before Maven's final summary; the full suite is therefore not claimed complete.

## Replay cutoff follow-up

- Bridge mode requires a positive `bridge-replay-cutoff-ms`; Kafka partition discovery starts from timestamp-derived offsets while a parsed-event filter rejects and counts missing/zero or older event timestamps. The exact cutoff boundary is accepted and normal v2 remains unaffected.
- The runbook defines cutoff arithmetic, signed per-partition `[timestampStartOffset,fencedEndOffset)` manifests, gap checks, dormant-expired-key reconciliation, and authoritative savepoint metadata plus isolated restore dry-run compatibility checks.
- Cutoff boundary test: 1 test, 0 failures/errors, BUILD SUCCESS. Package build: BUILD SUCCESS.
- The broader focused class rerun reached 26 passing tests before the pre-existing Mockito inline-agent self-attachment test errored in this environment; no product assertion failed. Docker remains unavailable.
