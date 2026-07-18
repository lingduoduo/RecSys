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
export OLD_JOB_JAR=/immutable/releases/recsys-before-partition-optimization.jar
export BRIDGE_JOB_JAR=/immutable/releases/recsys-kafka-v2.jar
export NEW_V2_JOB_JAR=/immutable/releases/recsys-kafka-v2.jar
export OLD_JOB_GRAPH_JSON=/immutable/change-evidence/old-job-graph.json
export BRIDGE_JOB_GRAPH_JSON=/immutable/change-evidence/bridge-job-graph.json
export CUTOVER_REFERENCE_MS=<approved-reference-epoch-ms>
export BRIDGE_REPLAY_CUTOFF_MS=<reference-minus-max-ttl-window-lateness-watermark-safety>
```

`OLD_JOB_JAR` is the exact pre-partition-optimization artifact currently deployed. Its Kafka source must have no stable UID, or its exact observed old UID must be recorded from the deployed graph; it must not be `kafka-movie-events-v2`. `NEW_V2_JOB_JAR` is this feature artifact and its Kafka source UID is exactly `kafka-movie-events-v2`. Record both artifacts' immutable repository coordinates and SHA-256 checksums in the change ticket:

```bash
shasum -a 256 "$OLD_JOB_JAR" "$NEW_V2_JOB_JAR"
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

## 2. Rebuild future-compatible state with a shadow bridge

Never restore the legacy job's generated operator IDs directly. First prove legacy-topic retention covers the maximum configured dedup/history/embedding/session TTL plus window lateness and watermark safety. Bridge mode is state-only: it must not write Redis or any production external sink. Launch this artifact from earliest in explicit bridge mode:

Derive `BRIDGE_REPLAY_CUTOFF_MS` as `CUTOVER_REFERENCE_MS - max(configured state TTLs) - window/lateness/watermark safety`. Record every input and the arithmetic. Kafka resolves a start offset per partition from this timestamp; a second parsed-event guard rejects older event times. Missing or zero event timestamps are unclassifiable and must be rejected, never guessed.

```bash
flink run -c com.recsys.online.flink.OnlineFeatureStreamingJob "$BRIDGE_JOB_JAR" \
  --bridge-mode true --bootstrap.servers "$BOOTSTRAP_SERVERS" \
  --bridge-replay-cutoff-ms "$BRIDGE_REPLAY_CUTOFF_MS" \
  --bridge-reference-time-ms "$CUTOVER_REFERENCE_MS" \
  --topic "$OLD_TOPIC" --group.id online-features-bridge-v1 \
  --expected-topic-partitions <legacy-partition-count> \
  --source-parallelism <legacy-partition-count> --operator-parallelism 24 \
  --max-parallelism 128 --checkpoint-dir <durable-checkpoint-uri>
```

The bridge source UID is `kafka-movie-events-bridge-v1`; its downstream UIDs and state schemas exactly match v2, while production sink UIDs terminate in no-op state-only sinks. Verify `$BRIDGE_JOB_GRAPH_JSON` contains the bridge source UID, every documented downstream UID, expected max parallelism, compatible state descriptors, and no Redis sink implementation. Reconcile rebuilt state only through controlled savepoint/state inspection or an approved isolated export—not production Redis writes. Abort on insufficient retention, failed checkpoints, nonzero lag, or reconciliation differences.

Before bridge startup, capture every partition's timestamp-derived start offset in a signed/checksummed manifest. At the producer fence capture each exclusive end offset. Replay exactly `[timestampStartOffset,fencedEndOffset)` and verify contiguous offsets, counts, and checksums prove no gaps or duplicates. Reconciliation compares active keys and explicitly confirms dormant keys expired before the horizon are absent.

The global cutoff only bounds Kafka reads. Each state branch independently admits events whose original `eventTime + configured TTL` is strictly greater than `CUTOVER_REFERENCE_MS`; equality is expired. Dedup, recent history, embeddings, and sessions persist their original event-time expiry. Flink TTL is only physical cleanup safety. Late events cannot move an active state's logical expiry or last-update time backward, and a gap at or beyond expiry resets that state before applying the new event.

`--allow-local-checkpoint-storage true` exists only for automated local/Docker tests. It is forbidden in staging and production. Production checkpoint URIs must use approved shared storage such as `s3`, `s3a`, `hdfs`, `gs`, `abfs`, `abfss`, `wasb`, or `wasbs`.

## 3. Fence producers and drain the old and bridge groups

Pause every producer deployment that writes movie events. Do not merely scale the consumer down: the fence must stop new writes while offsets drain. Record the fence time and verify the old consumer group's lag reaches zero.

```bash
kafka-consumer-groups.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --describe --group "$OLD_GROUP"
```

Repeat for both `$OLD_GROUP` and `online-features-bridge-v1` until every partition's `LAG` is `0`, and let the final legacy window close. If lag grows, a producer is still active; find and pause it before continuing.

## 4. Take a savepoint from the bridge

Before stopping, archive the running job's execution graph through the Flink UI/REST endpoint or the deployment platform's job-graph export. Use the platform's savepoint metadata inspector as a second source where available. Inspect the archived metadata, not the new source code:

```bash
jq -r '.. | objects | select((.name? // "") | test("kafka-movie-events"; "i")) \
  | [.name, (.uid // "<no-stable-uid>"), (.operator_id // .id // "<unknown-id>")] | @tsv' \
  "$OLD_JOB_GRAPH_JSON"
```

An authoritative result must show that the deployed old Kafka source has no stable UID or has a recorded UID different from `kafka-movie-events-v2`. **Abort the cutover** if the old source UID equals `kafka-movie-events-v2`, if the graph belongs to a different deployed artifact, or if the source identity cannot be established. In those cases, produce and review a state-migration plan before any restore.

```bash
flink stop --savepointPath "$SAVEPOINT_DIR" <bridge-job-id>
```

Capture the returned bridge savepoint URI and retain it through the recovery window. Downstream stateful operators use stable UIDs, including `event-idempotency-v1`, `recent-movies-v1`, `user-embedding-feature-v1`, `session-feature-v1`, `movie-metrics-v1`, `topk-partial-v1`, and `topk-final-v1`, plus the Redis sink UIDs. Do not rename or remove them during this migration.

The source is deliberately different: `kafka-movie-events-v2` is generation-specific. KafkaSource savepoints contain enumerator and partition-split state, including topic partitions and offsets. Reusing the old source UID could bind old-topic split state to the v2 source, skip v2 records, or seek meaningless offsets. The cutover must leave the old source state unrestored while restoring the stable downstream state.

## 5. Restore against the new topic and group

Submit `NEW_V2_JOB_JAR`, not the old artifact and not a mutable `app.jar` alias. The essential arguments are:

```bash
flink run -s <savepoint-uri> -n \
  -c com.recsys.online.flink.OnlineFeatureStreamingJob "$NEW_V2_JOB_JAR" \
  --bootstrap.servers "$BOOTSTRAP_SERVERS" \
  --topic "$NEW_TOPIC" --group.id "$NEW_GROUP" \
  --expected-topic-partitions 24 --source-parallelism 24 \
  --operator-parallelism 24 --max-parallelism 128 \
  --top-k-bucket-count 24 --final-top-k-parallelism 1 \
  --top-k-allowed-lateness-ms 5000 --watermark-idle-timeout-ms 30000 \
  --checkpoint-dir <durable-checkpoint-uri>
```

`-n` (long form `--allowNonRestoredState`) is mandatory because bridge source state is unmatched: `kafka-movie-events-bridge-v1` becomes `kafka-movie-events-v2`. Abort unless the only non-restored state is the bridge source. Execution-graph UIDs/max parallelism are necessary but not serializer proof: inspect bridge savepoint metadata and complete an isolated bridge-to-v2 restore dry-run as the authoritative serializer/state-schema compatibility gate. Direct restore from legacy generated IDs is forbidden. Because the v2 source is new and `$NEW_GROUP` has no committed offsets, its configured `OffsetsInitializer.earliest()` starts at the beginning of the fenced, initially empty v2 topic.

Topic generations require artifact generations. A future `movie_events_v3` cutover must ship a reviewed artifact whose source UID is `kafka-movie-events-v3`; changing only `--topic` while reusing `NEW_V2_JOB_JAR` is forbidden because it would reuse the v2 source identity.

Keeping producers fenced until the restored job is healthy prevents ambiguity. The Top-K path uses event time, tolerates five seconds of out-of-order data, and marks silent source partitions idle after 30 seconds so one empty partition cannot stall all watermarks.

## 6. Switch producers and validate

Before resuming producers, capture the activation/start offset for every v2 partition. Store and checksum the immutable file in the change evidence:

```bash
kafka-get-offsets.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --topic "$NEW_TOPIC" --time -1 | sort -t: -k2,2n > v2-start-offsets.tsv
shasum -a 256 v2-start-offsets.tsv
```

Set `ONLINE_EVENTS_KAFKA_TOPIC=movie_events_v2` with `ONLINE_EVENTS_KAFKA_ENABLED=true`, then resume producers. Online movie events are keyed by normalized positive `userId`, preserving per-user partition order. The first accepted record at or after a recorded start offset is the recovery-phase boundary.

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

## 7. Recover or roll back

### Before the first accepted v2 event

Pre-activation rollback is allowed because no v2-era input or downstream state exists. Stop the new job, restore `$OLD_JOB_JAR` with its exact old configuration from the original savepoint using `$OLD_TOPIC` and `$OLD_GROUP`, point the still-fenced producers back to `$OLD_TOPIC`, resume them, and validate checkpoints, lag, Redis freshness, and recommendations.

### After the first accepted v2 event

A simple restore of the pre-cutover savepoint is forbidden: it would discard or regress state derived from v2 events. Keep producers and processing on v2 and fix forward whenever possible.

Moving v2 back to the old topic requires a separately approved replay and reconciliation operation:

1. Fence all producers and drain `$NEW_GROUP` to zero. After the fence, capture each v2 partition's exclusive end offset and committed offset; store and checksum both immutable files:
   ```bash
   kafka-get-offsets.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
     --topic "$NEW_TOPIC" --time -1 | sort -t: -k2,2n > v2-end-offsets.tsv
   kafka-consumer-groups.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
     --describe --group "$NEW_GROUP" > v2-committed-offsets.txt
   shasum -a 256 v2-start-offsets.tsv v2-end-offsets.tsv v2-committed-offsets.txt
   ```
   Abort if any committed offset is below its recorded end offset.
2. Stop the v2 job with a recovery savepoint. Export exactly the half-open offset range `[start,end)` recorded for every v2 partition—no more and no less—to immutable storage. Selection is by v2 partition and Kafka offset only; event time and old-topic offsets must not define or trim the range. Include key, partition, offset, event ID, event time, and original value bytes.
3. Produce a signed/checksummed replay manifest listing every partition's start, exclusive end, expected count (`end - start`), exported count, payload checksum, and total count. Abort on a gap, duplicate source offset, count mismatch, or checksum mismatch.
4. Replay the complete manifest into `$OLD_TOPIC`, grouped/keyed by normalized positive user ID. Preserve each user's v2 source order; do not repartition by event ID and do not interleave a user's records nondeterministically. Preserve original event IDs so `event-idempotency-v1` suppresses at-least-once duplicates.
5. Restore the old job only under an approved state plan: either transform/reconcile the v2 recovery savepoint for the old graph or restore the old savepoint and replay the complete manifest. Never attach v2 Kafka split state to the old source.
6. Reconcile Redis history, embeddings, sessions, metrics, and Top-K against an authoritative v2 snapshot. Investigate missing IDs, duplicate IDs, per-user ordering violations, count differences, and freshness regressions.
7. Verify replay consumer lag is zero, checkpoints complete without failure, exported/replayed counts and checksums reconcile to every partition's `[start,end)` manifest, all manifest event IDs are accounted for exactly once after deduplication, per-user results are ordered, and sampled/all feasible Redis features match the authoritative snapshot before producers resume on the old topic.

Do not attempt to shrink `movie_events_v2`; Kafka partition counts cannot be reduced. If the restore rejects state compatibility, keep producers fenced and restore the exact prior artifact rather than discarding non-restored state.

## 8. Retire the old topic

Keep the old topic, original savepoint, v2 recovery savepoints, and replay data through the agreed recovery window and required data-retention/audit period. After change approval confirms the v2-to-old replay path is no longer required, verify no producer or consumer references `$OLD_TOPIC`, archive required offsets and manifests, and then delete it:

```bash
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --delete --topic "$OLD_TOPIC"
```

Topic deletion is irreversible once broker retention removes its data; never include it in the cutover transaction itself.
