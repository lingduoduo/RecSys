# Task 5B report: real Kafka/Flink load gate

## Outcome

Replaced `KafkaFlinkPartitionLoadTest`'s post-hoc modeled checkpoint and Top-K counters with one live Testcontainers Kafka → production `KafkaSource` → production partition graph → instrumented sinks execution on a Flink `MiniCluster`.

The opt-in `load,docker` test now:

- runs five full one-second steady-state intervals and requires at least 50,000 Kafka acknowledgements per second in every interval;
- consumes concurrently through the Task 5A `buildEventStream` / `buildPartitionGraph` seams and proves acknowledged input reaches the actual dedup and two-stage Top-K graph;
- samples committed Kafka consumer-group lag during the run and waits for recovery to zero after production stops;
- reads completed checkpoint counts from `MiniCluster.getExecutionGraph(...).getCheckpointStatsSnapshot()`;
- reads busy, idle, and backpressured time from the real final Top-K execution vertices' `IOMetrics`, rejecting any continuously backpressured subtask;
- verifies acknowledged-record equality, same-user monotonic event order, real Top-K snapshot output, broad partition distribution, and the 2x-median skew policy after subtracting the declared hot-user fixture.

There are no synchronous modeled workload/checkpoint/backlog counters left.

## Verification

- `mvn -Pstreaming-flink -DskipTests test-compile` — **PASS**
- `mvn -Pstreaming-flink test -Dtest=OnlineFeatureStreamingJobTest,KafkaTopicPartitionValidatorTest` — **PASS** (26 tests, 0 failures/errors, 3 honest Docker-dependent skips)
- Docker load execution — **SKIPPED honestly** because the configured Docker socket refused connections. The class retains `@Testcontainers(disabledWithoutDocker = true)` and the `load,docker` tags.

Run on the benchmark host with:

```bash
mvn -Pstreaming-flink test -DexcludedGroups="" -Dgroups=load,docker -Dtest=KafkaFlinkPartitionLoadTest
```
