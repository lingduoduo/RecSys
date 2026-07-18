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

Capture the returned savepoint URI. Verify the old job reaches `FINISHED` and retain the savepoint for the entire rollback window. The stateful operators use stable UIDs, including `kafka-movie-events-v2`, `event-idempotency-v1`, `recent-movies-v1`, `user-embedding-feature-v1`, `session-feature-v1`, `movie-metrics-v1`, `topk-partial-v1`, and `topk-final-v1`, plus the Redis sink UIDs. Do not rename or remove them during this migration.

## 4. Restore against the new topic and group

Submit the same artifact from the savepoint. The essential arguments are:

```bash
flink run -s <savepoint-uri> -c com.recsys.online.flink.OnlineFeatureStreamingJob app.jar \
  --bootstrap.servers "$BOOTSTRAP_SERVERS" \
  --topic "$NEW_TOPIC" --group.id "$NEW_GROUP" \
  --expected-topic-partitions 24 --source-parallelism 24 \
  --operator-parallelism 24 --max-parallelism 128 \
  --top-k-bucket-count 24 --final-top-k-parallelism 1 \
  --top-k-allowed-lateness-ms 5000 --watermark-idle-timeout-ms 30000 \
  --checkpoint-dir <durable-checkpoint-uri>
```

The Kafka source begins at restored offsets when source state is compatible; otherwise the new group has no committed offsets and the configured fallback is earliest. Keeping producers fenced prevents ambiguity. The Top-K path uses event time, tolerates five seconds of out-of-order data, and marks silent source partitions idle after 30 seconds so one empty partition cannot stall all watermarks.

## 5. Switch producers and validate

Set `ONLINE_EVENTS_KAFKA_TOPIC=movie_events_v2` with `ONLINE_EVENTS_KAFKA_ENABLED=true`, then resume producers. Online movie events are keyed by normalized positive `userId`, preserving per-user partition order.

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

## 6. Roll back

Rollback is safe only while the old topic, old deployment configuration, and savepoint are retained.

1. Fence producers again and allow the new group to drain to zero.
2. Stop the new Flink job with a new diagnostic savepoint.
3. Restore the old artifact/configuration from the original savepoint using `$OLD_TOPIC` and `$OLD_GROUP`.
4. Point producers back to `$OLD_TOPIC`, resume them, and validate checkpoints, lag, Redis freshness, and recommendations.

Do not attempt to shrink `movie_events_v2`; Kafka partition counts cannot be reduced. If the restore rejects state compatibility, keep producers fenced and restore the exact prior artifact rather than discarding non-restored state.

## 7. Retire the old topic

Keep the old topic and original savepoint through the agreed rollback window and required data-retention/audit period. After change approval confirms rollback is no longer required, verify no producer or consumer references `$OLD_TOPIC`, archive any required offsets, and then delete it:

```bash
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --delete --topic "$OLD_TOPIC"
```

Topic deletion is irreversible once broker retention removes its data; never include it in the cutover transaction itself.
