# Task 5A report: real Kafka/Flink partition contract

## Outcome

Replaced the rejected hybrid test (raw Kafka consumer plus direct function/harness calls) with a Docker-tagged end-to-end contract test. The test now publishes keyed records to a real 24-partition Testcontainers Kafka broker and consumes them through the production `KafkaSource` in an embedded Flink MiniCluster.

## Production seams

- `buildEventStream` is package-private so the integration test uses the production Kafka source construction.
- `validateKafkaTopic` is a package-private extraction of the startup validation path; `main` calls this same seam.
- `buildPartitionGraph` is a narrow package-private graph builder using the production user-keyed `DeduplicateEventsFunction`, `PartialTopKWindowFunction`, and `FinalTopKWindowFunction`. It assigns the production stable UIDs and max parallelism 128.

No Redis sinks or unrelated production operators are included in this seam.

## Integration coverage

- Publishes ordered records for 96 users with Kafka keys derived from `userId` and verifies broker metadata shows distribution across at least half of 24 partitions.
- Collects records after the real Kafka source, `keyBy`, shuffle, and production dedup operator, then verifies exact per-user order.
- Advances watermarks and observes output from the real production two-stage Top-K operators.
- Calls the actual startup validation seam against a real 23-partition topic and expects the configured 24-partition mismatch failure.
- Triggers a real MiniCluster savepoint, cancels the job, restores the same stable-UID graph with ordinary operator parallelism changed from 4 to 6, replays a saved event ID, and verifies dedup state continuity while a new event continues through the graph.

The deterministic test sink is intentionally process-local and is used only to observe MiniCluster outputs; it does not substitute for operator state or repartition state manually.

## Verification

- `mvn -Pstreaming-flink -DskipTests test-compile` — build succeeded after compiling all streaming tests.
- `mvn -Pstreaming-flink test -Dtest=OnlineFeatureStreamingJobTest,KafkaTopicPartitionValidatorTest -DexcludedGroups=load,docker` — 26 tests run, 0 failures, 0 errors, 3 Docker-dependent Redis tests skipped.
- `mvn -Pstreaming-flink test -Dtest=KafkaFlinkPartitionIntegrationTest -DexcludedGroups=load` — the two Docker-tagged tests were honestly skipped because no Docker daemon was reachable (`/Users/linghuang/.docker/run/docker.sock` refused and `/var/run/docker.sock` absent). Testcontainers reported 2 tests run, 2 skipped, and Maven build success.

## Remaining environment verification

Run the following with Docker available to execute Kafka plus MiniCluster behavior rather than skip it:

```bash
mvn -Pstreaming-flink test -Dtest=KafkaFlinkPartitionIntegrationTest -DexcludedGroups=load
```

The load-test class was not changed and no load-performance claims are made here.

## Review follow-up

The MiniCluster assertion now selects snapshots by their exact event-time window end rather than by arrival order. For the first closed window it computes an independent oracle from accepted input and asserts the exact ten movie IDs, exact scores, deterministic score/id ordering, truncation to K, absence of duplicates, and that the result spans multiple production movie buckets.

The restore phase now leaves the `[6000,7000)` Top-K window open before triggering the savepoint. After restoring with ordinary parallelism changed from 4 to 6 (max parallelism remains 128), it adds the remaining window records and advances the watermark. The exact oracle for the resulting `7000` snapshot includes both pre-savepoint and post-restore contributions, directly proving window-state continuity through rescaling.

Dedup restore coverage now also replays the saved event ID twice: the same-user copy is suppressed, while the identical event ID under another user is accepted. The sink assertion checks that the sole accepted copy after restore belongs to the other user.

Fresh follow-up verification:

- `mvn -Pstreaming-flink -DskipTests test-compile` — build success, 198 test sources compiled.
- Focused non-Docker command — 26 tests, 0 failures, 0 errors, 3 unrelated Docker-dependent Redis skips.
- Docker contract command — 2 tests discovered and 2 honestly skipped because the Docker socket remains unavailable; build success.
