# Kafka partition cutover runbook

This runbook moves online movie events to `movie_events_v2` with exactly 24 partitions. The Flink job refuses to start when `--expected-topic-partitions` differs from the broker, and the producer rejects missing, zero, or negative user IDs instead of publishing an unkeyed record. Use a new consumer group during cutover so the old deployment remains available for rollback.

Set deployment-specific values before running the commands:

```bash
export BOOTSTRAP_SERVERS=localhost:9092
export OLD_TOPIC=movie_events
export NEW_TOPIC=movie_events_v2
export OLD_GROUP=online-features
export NEW_GROUP=online-features-v2
export FLINK_JOB_ID=<running-job-id>
export SAVEPOINT_DIR=file:///durable/flink/savepoints
```

## 1. Create and verify the fenced destination

Create the topic before changing any producer. Use the production replication factor, not the single-broker example value, when running in a cluster.

```bash
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --create --if-not-exists --topic "$NEW_TOPIC" \
  --partitions 24 --replication-factor 1
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --describe --topic "$NEW_TOPIC"
```

The description must show `PartitionCount: 24` and partitions 0 through 23. Stop if it does not: neither the replay producer nor the Flink startup validator permits a different count.

## 2. Fence producers and drain the old group

Pause every producer deployment that writes movie events. Do not merely scale the consumer down: the fence must stop new writes while offsets drain. Record the fence time and verify the old consumer group's lag reaches zero.

```bash
kafka-consumer-groups.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --describe --group "$OLD_GROUP"
```

Repeat until each partition's `LAG` is `0`. If lag grows, a producer is still active; find and pause it before continuing.

## 3. Take a savepoint and stop the old job

```bash
flink stop --savepointPath "$SAVEPOINT_DIR" "$FLINK_JOB_ID"
```

Capture the returned savepoint URI. Verify the old job reaches `FINISHED` and retain the savepoint through the recovery window. Downstream stateful operators use stable UIDs, including `event-idempotency-v1`, `recent-movies-v1`, `user-embedding-feature-v1`, `session-feature-v1`, `movie-metrics-v1`, `topk-partial-v1`, and `topk-final-v1`, plus the Redis sink UIDs. Do not rename or remove them during this migration.

The source is deliberately different: `kafka-movie-events-v2` is generation-specific. KafkaSource savepoints contain enumerator and partition-split state, including topic partitions and offsets. Reusing the old source UID could bind old-topic split state to the v2 source, skip v2 records, or seek meaningless offsets. The cutover must leave the old source state unrestored while restoring the stable downstream state.

## 4. Restore against the new topic and group

Submit the same artifact from the savepoint. The essential arguments are:

```bash
flink run -s <savepoint-uri> --allowNonRestoredState \
  -c com.recsys.online.flink.OnlineFeatureStreamingJob app.jar \
  --bootstrap.servers "$BOOTSTRAP_SERVERS" \
  --topic "$NEW_TOPIC" --group.id "$NEW_GROUP" \
  --expected-topic-partitions 24 --source-parallelism 24 \
  --operator-parallelism 24 --max-parallelism 128 \
  --top-k-bucket-count 24 --final-top-k-parallelism 1 \
  --top-k-allowed-lateness-ms 5000 --watermark-idle-timeout-ms 30000 \
  --checkpoint-dir <durable-checkpoint-uri>
```

`--allowNonRestoredState` (short form `-n`) is mandatory here: it permits the old generation's source state to be discarded while matching the stable downstream UIDs from the savepoint. Review the submission log and confirm only the expected old source state is non-restored. Because the v2 source is new and `$NEW_GROUP` has no committed offsets, its configured `OffsetsInitializer.earliest()` starts at the beginning of the fenced, initially empty v2 topic. It must never restore old-topic offsets into v2.

Keeping producers fenced until the restored job is healthy prevents ambiguity. The Top-K path uses event time, tolerates five seconds of out-of-order data, and marks silent source partitions idle after 30 seconds so one empty partition cannot stall all watermarks.

## 5. Switch producers and validate

Set `ONLINE_EVENTS_KAFKA_TOPIC=movie_events_v2` with `ONLINE_EVENTS_KAFKA_ENABLED=true`, then resume producers. Online movie events are keyed by normalized positive `userId`, preserving per-user partition order. Record the timestamp and offset of the first accepted v2 event: it is the recovery-phase boundary.

Validate all of the following before declaring the cutover healthy:

```bash
kafka-consumer-groups.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --describe --group "$NEW_GROUP"
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --describe --topic "$NEW_TOPIC"
```

- Flink remains `RUNNING`; completed checkpoint count increases and failed checkpoint count does not.
- The new consumer group advances on all active partitions and aggregate lag returns to the normal operating band.
- Records reach multiple partitions. For ordinary users (excluding explicitly identified hot users), the busiest active partition must be no more than **twice the median** active-partition volume.
- Event-time Top-K windows advance, `topk-partial-v1` and `topk-final-v1` have no sustained backpressure, and Redis freshness timestamps advance.
- Missing/invalid user-key rejection and publisher queue rejection metrics do not spike.
- The opt-in load contract is 50,000 events/s, zero final consumer lag, completed checkpoints, and no failed checkpoints. This is a pre-cutover capacity guard, not a claim that every production environment sustains that rate without its own sizing test.

## 6. Recover or roll back

### Before the first accepted v2 event

Pre-activation rollback is allowed because no v2-era input or downstream state exists. Stop the new job, restore the exact old artifact/configuration from the original savepoint using `$OLD_TOPIC` and `$OLD_GROUP`, point the still-fenced producers back to `$OLD_TOPIC`, resume them, and validate checkpoints, lag, Redis freshness, and recommendations.

### After the first accepted v2 event

A simple restore of the pre-cutover savepoint is forbidden: it would discard or regress state derived from v2 events. Keep producers and processing on v2 and fix forward whenever possible.

Moving v2 back to the old topic requires a separately approved replay and reconciliation operation:

1. Fence all producers and drain `$NEW_GROUP` to zero; record per-partition end and committed offsets.
2. Stop the v2 job with a recovery savepoint and export every accepted v2 record plus its key, partition, offset, event ID, and event time to immutable storage.
3. Build a replay manifest grouped by normalized positive user ID. Preserve each user's v2 source order; do not repartition by event ID and do not interleave a user's records nondeterministically.
4. Establish the old topic's recorded cutover boundary. Replay only v2-era records after that boundary into `$OLD_TOPIC`, keyed by the same user ID. Preserve original event IDs so `event-idempotency-v1` suppresses at-least-once duplicates.
5. Restore the old job only under an approved state plan: either transform/reconcile the v2 recovery savepoint for the old graph or restore the old savepoint and replay the complete manifest. Never attach v2 Kafka split state to the old source.
6. Reconcile Redis history, embeddings, sessions, metrics, and Top-K against an authoritative v2 snapshot. Investigate missing IDs, duplicate IDs, per-user ordering violations, count differences, and freshness regressions.
7. Verify replay consumer lag is zero, checkpoints complete without failure, all manifest event IDs are accounted for exactly once after deduplication, per-user results are ordered, and sampled/all feasible Redis features match the authoritative snapshot before producers resume on the old topic.

Do not attempt to shrink `movie_events_v2`; Kafka partition counts cannot be reduced. If the restore rejects state compatibility, keep producers fenced and restore the exact prior artifact rather than discarding non-restored state.

## 7. Retire the old topic

Keep the old topic, original savepoint, v2 recovery savepoints, and replay data through the agreed recovery window and required data-retention/audit period. After change approval confirms the v2-to-old replay path is no longer required, verify no producer or consumer references `$OLD_TOPIC`, archive required offsets and manifests, and then delete it:

```bash
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --delete --topic "$OLD_TOPIC"
```

Topic deletion is irreversible once broker retention removes its data; never include it in the cutover transaction itself.
