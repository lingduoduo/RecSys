# Kafka and Flink Partition Optimization Design

## Goal

Scale online movie-event processing to 50,000 events per second while controlling partition skew, removing single-task aggregation bottlenecks, preserving strict per-user ordering, and supporting safe future partition-count increases.

This is the first of four partition-optimization projects. Redis record shards, Redis Top-K shards, and MySQL table partitioning will receive separate design and implementation cycles after this project.

## Current Constraints

The local movie-event topic is created with one partition and populated through a value-only console producer. `OnlineFeatureStreamingJob` consumes values without Kafka keys, then repartitions records inside Flink for event deduplication, user history, user embeddings, sessions, and movie metrics. Final Top-K calculation uses `windowAll`, which forces the complete window through one task.

Strict ordering is required for events belonging to the same user. Consequently, a single hot user cannot be split across Kafka partitions or parallel user-state operators. Skew mitigation must distribute different users while preserving each user's ordered stream.

## Target Capacity

- Sustained input: 50,000 movie events per second.
- Initial production topic: 24 partitions.
- Kafka and Flink parallelism are independently configurable up to the useful partition or key cardinality limit.
- The design must permit a later partition increase without an in-place remapping that violates per-user order.

The exact broker count, replication factor, retention, and storage sizing remain deployment concerns. Production topics require the platform's normal replication and durability policy; the single-broker local environment may retain replication factor one.

## Keyed Publishing Contract

Online movie events are published with normalized `userId` as the Kafka record key. All producers targeting the partitioned movie-event topic must use the same UTF-8 key representation and Kafka's configured partitioner.

Publishing rejects events whose JSON cannot be parsed or whose `userId` is missing or invalid. Rejections are counted by reason and logged without the full event body or sensitive payload fields. Unkeyed events are never silently assigned to arbitrary partitions.

The Kafka transport gains an explicit key-aware publishing seam rather than extracting arbitrary fields implicitly inside a generic transport. Online movie-event publishing supplies the user-key extractor. A/B exposure events keep their separate topic and configuration and are not coupled to this movie-event partition contract.

The local producer script must use a small keyed producer path capable of parsing each NDJSON event and producing `(userId, eventJson)`. The existing value-only `kafka-console-producer` invocation is not sufficient because it cannot reliably derive a JSON field as the key.

## Versioned Topic Strategy

The new topic is `movie_events_v2` with 24 partitions. Partition count is immutable for an active topic generation. A later resize creates `movie_events_v3` rather than increasing `movie_events_v2` in place.

The Flink job accepts:

- topic name;
- expected topic partition count;
- source parallelism;
- operator parallelism defaults and targeted overrides; and
- a stable consumer group identifier per active generation.

At startup, deployment validation compares the configured expected count with Kafka metadata and fails before normal processing when they differ. This prevents a producer or consumer from unknowingly operating against an in-place partition change.

## Flink Processing Topology

The Kafka source runs at configurable parallelism, capped at 24 for `movie_events_v2`. Records remain keyed by `userId` at Kafka ingress, but Flink retains explicit downstream `keyBy` boundaries because each operator has its own state and distribution requirement:

- deduplication by `userId`, with recent event IDs stored inside per-user keyed state;
- recent history and user embedding by `userId`;
- session features by `(userId, sessionId)`;
- movie metrics by `(movieId, metricKind)`.

Downstream stateful operators receive stable UIDs so savepoints can map state across deployments and parallelism changes. The Kafka source UID includes the topic generation and changes for every versioned topic. A topic cutover restores downstream keyed state with non-restored source state explicitly allowed; it must never bind old-topic Kafka split state to the new source. Maximum parallelism is set deliberately and remains stable for the topic generation; ordinary parallelism may change within that bound through savepoint restore.

### Two-stage Top-K

The global `windowAll` operator is replaced with two stages:

1. Assign each eligible event to a deterministic movie bucket and compute a bounded partial Top-K for each event-time window and bucket in parallel.
2. Route only the bounded partial lists to a final merge operator keyed by window end. An event-time timer fires only after the downstream watermark proves every upstream bucket has emitted all on-time partials, then computes the exact global Top-K.

Each partial result contains at most `topK` entries, so the final operator processes `bucketCount × topK` candidates rather than every event. Bucket count is configurable, positive, and no greater than the useful upstream parallelism. Bucket assignment uses a stable hash of `movieId`; changing bucket count requires a savepoint-aware deployment and explicit compatibility review.

The final Redis Top-K and trend payload formats remain unchanged. Final-merge state is cleared after the configured allowed-lateness interval; partials arriving after cleanup are rejected and counted. Processing-time timers are not used for cross-subtask completion because they cannot prove all shuffled partials have arrived.

## Safe Cutover Procedure

Moving from the current topic to `movie_events_v2` uses a fenced, drain-and-restore cutover:

1. Create `movie_events_v2` with 24 partitions and validate its configuration.
2. Deploy the new keyed producer capability without switching its active topic.
3. Fence or pause old-topic producers at a recorded boundary.
4. Allow the old consumer to drain to zero lag.
5. Trigger and verify a Flink savepoint.
6. Stop the old job only after the savepoint succeeds.
7. Before the production cutover, run a shadow bridge deployment against the retained old topic from its earliest required offsets. The bridge uses its own source UID and consumer group but the future-compatible v2 downstream UIDs and state schemas. It must catch up to zero lag and prove retention covers the full deduplication/state rebuild horizon.
8. Fence producers, let open windows close, drain both old and bridge consumers, and take the cutover savepoint from the bridge job.
9. Restore the new job from that bridge savepoint using the generation-specific `kafka-movie-events-v2` source UID, explicitly allowing only the bridge Kafka source state to remain unrestored.
10. Switch producers to `movie_events_v2` and resume writes.
11. Verify per-partition ingestion, user ordering, output freshness, checkpoint health, and bounded lag.
12. Retire the old topic only after the rollback window expires.

The bridge is a rebuild from retained Kafka events, not a direct restore of generated operator IDs from the legacy job. Cutover aborts if retention cannot rebuild the required state horizon, bridge outputs do not reconcile with the active job/Redis view, or drain, savepoint, restore, topic validation, or healthy-checkpoint verification fails. Before v2 producers activate, rollback may resume the old job and topic from the recorded boundary. After any v2 event is accepted, simple rollback to the pre-cutover savepoint is forbidden because it would lose or regress v2-era state. Post-activation recovery is fix-forward on v2 unless an explicit, verified v2-to-old replay and state-reconciliation procedure is executed. The procedure does not dual-write because independent topics cannot provide a single atomic order for the same user.

## Skew and Backpressure Policy

Per-user ordering takes precedence over parallelizing an individual hot user's stream. Operational alerts identify rather than salt such a key. A partition is considered skewed when its sustained event rate or lag exceeds twice the median across active partitions over the monitoring window.

Required signals include:

- input events and bytes per Kafka partition;
- consumer lag per partition and maximum lag age;
- malformed or unkeyed event rejection count by reason;
- Flink busy, idle, and backpressured time per operator and subtask;
- checkpoint duration, size, failures, and completed-checkpoint age;
- restart count and savepoint restore failures; and
- partial and final Top-K input counts and processing duration.

Scaling adds Flink task slots or raises operator parallelism only when Kafka partitions and key cardinality can use them. Persistent skew caused by a few users requires product-level rate control or isolation rather than breaking the ordering contract.

## Failure Handling

- Topic metadata mismatch fails startup.
- Invalid producer events are rejected before Kafka send and recorded by reason.
- Producer send failures use the existing bounded asynchronous failure reporting; silent success is forbidden.
- Production execution requires durable checkpoint storage. Missing production checkpoint configuration fails deployment validation.
- A failed checkpoint or savepoint blocks cutover but does not delete the last known-good artifact.
- Restore failure leaves the old topic and job available for rollback.
- Deduplication remains event-ID-based inside user-keyed state and protects downstream features from at-least-once replay across recovery. The job must not repartition by event ID before user-state operators because that would weaken strict same-user ordering.

## Testing

### Unit tests

- normalized user-key extraction;
- missing, malformed, and invalid user rejection;
- stable movie bucket assignment;
- bucket-count validation;
- bounded partial Top-K output;
- exact final Top-K merge, including ties and duplicate movie candidates; and
- configuration validation for partition count and parallelism bounds.

All behavior changes follow red-green-refactor: the focused test fails for the expected reason before production code changes.

### Integration tests

A multi-partition Kafka and parallel Flink test environment verifies:

- all events for one user retain order;
- different users distribute across partitions;
- deduplication remains correct under replay;
- user, session, and movie keyed state produces unchanged outputs;
- partial plus final aggregation equals a single-threaded Top-K oracle;
- a savepoint restores successfully at changed operator parallelism; and
- topic partition-count mismatch prevents startup.

Docker-tagged integration tests may remain outside the default Maven suite but must run in CI for this feature.

### Load test

The representative workload must sustain 50,000 events per second with:

- no acknowledged event loss;
- strict same-user ordering;
- no sustained partition rate or lag above twice the median, excluding an explicitly identified hot-user fixture;
- bounded consumer-lag recovery after a burst;
- completed checkpoints throughout the steady-state interval; and
- no continuously backpressured single Top-K subtask.

Exact lag and checkpoint-duration SLO values are recorded from the benchmark environment before production rollout because they depend on broker count, task slots, state size, and storage latency.

## Rollout

Roll out first in a staging environment with representative key distribution, then perform a production canary cutover while monitoring the required signals. Keep the old topic and last known-good savepoint through the rollback window. Promote only after ordering probes, feature freshness, Top-K equivalence, checkpoint health, and lag recovery pass.

## Non-Goals

- Optimizing Redis record shards.
- Optimizing Redis Top-K storage shards.
- Adding MySQL table partitions.
- Splitting a single user's ordered stream with salting.
- Changing Redis feature payload schemas.
- Combining A/B exposure traffic with movie-event traffic.
- Choosing production broker hardware, replication factor, or retention without deployment data.
