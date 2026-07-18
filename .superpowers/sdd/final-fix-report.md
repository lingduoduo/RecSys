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
