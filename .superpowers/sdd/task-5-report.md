# Task 5 Report: Multi-Partition Integration and 50k Load Guard

## Status

Implemented the scoped Docker-tagged Kafka/Flink integration suite and opt-in load guard.

## Changes

- Added `org.testcontainers:kafka` as a test dependency.
- Added a 24-partition Kafka integration suite covering:
  - keyed same-user order across event IDs that vary independently of the key;
  - representative distribution across at least 12 partitions;
  - keyed Flink deduplication and same-user replay suppression;
  - preservation of the same event ID for a different user;
  - real Kafka topic partition mismatch rejection;
  - exact two-stage Top-K equality with a single-stage oracle across 24 buckets;
  - keyed dedup state snapshot/repartition/restore from parallelism 1 to 2 with max parallelism 128.
- Added a deterministic, opt-in 250,000-event load guard (`5 * 50,000`) with:
  - fixed-seed Zipf-like users and an explicit 20% hot-user fixture;
  - acknowledged-send throughput fixed at a minimum of 50,000 events/sec;
  - acknowledged and consumed counts;
  - per-partition traffic and at least 12 active partitions;
  - end-offset lag recovery to zero;
  - deterministic Top-K bucket backlog/checkpoint-interval measurements.

## Verification

- `mvn -Pstreaming-flink -DskipTests test-compile`
  - PASS (`BUILD SUCCESS`; 198 test sources compiled).
- `mvn -Pstreaming-flink test -DexcludedGroups=load -Dtest=KafkaFlinkPartitionIntegrationTest`
  - Environment-limited: 5 tests discovered, 5 skipped because Docker was unavailable.
  - Exact limitation: `~/.docker/run/docker.sock` refused connections and `/var/run/docker.sock` did not exist.
- `mvn -Pstreaming-flink test -DexcludedGroups="" -Dgroups=load,docker -Dtest=KafkaFlinkPartitionLoadTest`
  - Environment-limited: 1 test discovered, 1 skipped for the same Docker limitation.
  - No throughput result was recorded; the 50,000 events/sec target was retained unchanged.
- `mvn -Pstreaming-flink test -Dtest=OnlineFeatureStreamingJobTest,KafkaTopicPartitionValidatorTest`
  - Environment-limited/failing: 26 tests run, 3 skipped, 2 failures, 3 errors.
  - Existing Mockito inline mock maker could not self-attach under the Amazon JDK 17 sandbox; Docker-backed Redis tests were skipped. The failures do not originate in the Task 5 files.
- `git diff --check`
  - PASS.

## Self-review / concerns

- Docker execution and therefore the actual 50k capacity result remain unverified on this host.
- The load test measures Kafka acknowledgements and consumer lag directly. Its checkpoint and Top-K backlog counters are deterministic in-process guard measurements, not Flink REST metrics from a deployed cluster; a benchmark deployment should additionally scrape completed checkpoint and backpressure metrics.
- The Kafka producer intentionally uses `acks=all`, batching, linger, and LZ4; the threshold is not reduced or made configurable downward.
- Unrelated dirty reports and planning documents were preserved and excluded from the scoped commit.
