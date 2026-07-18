# Durable Eventual Consistency Design

## Objective

Guarantee durable, at-least-once propagation of accepted API events and saga transitions while preserving the recommendation system's asynchronous architecture. Add bounded read-your-writes behavior, deterministic Redis updates, actionable consistency metrics, and automated reconciliation.

## Scope

This design covers:

- a MySQL transactional outbox for online API events and saga transitions;
- a durable MySQL saga state store;
- reliable Kafka and SQS relay workers;
- Kafka idempotent producer configuration;
- read-your-writes consistency tokens;
- atomic, deterministically ordered Redis Top-K updates;
- consistency and delivery metrics; and
- a 24-hour reconciliation CronJob.

Existing tokenless recommendation requests retain their replica-backed, cache-assisted eventual-consistency behavior. The design does not introduce distributed transactions, Kafka Connect, Debezium, or exactly-once delivery across external brokers.

## Architecture

MySQL is the durable system of record for outbox events and production saga state. An API request synchronously commits its event to the outbox before reporting acceptance. A saga transition updates the saga row and inserts the corresponding outbox event in one MySQL transaction.

Dedicated relay workers claim outbox rows and publish them asynchronously to Kafka or SQS. Broker acknowledgements advance outbox state. At-least-once retries are safe because event IDs are stable, Kafka records are keyed, consumers deduplicate events, and Redis feature writes compare deterministic logical versions.

API event responses return signed consistency tokens. A later recommendation request may present the token to request read-your-writes behavior. The service bypasses replicas and local caches, checking primary Redis lineage for up to two seconds. It serves the recommendation after the event is visible or returns `202 Accepted` with `Retry-After: 1` when propagation is still pending.

A standalone Kubernetes CronJob reconciles the last 24 hours of delivered outbox events against Redis lineage and republishes missing events idempotently.

## MySQL Data Model

### `event_outbox`

Each row contains:

- `event_id BINARY(16)` as the globally unique primary key;
- `aggregate_type`, `aggregate_id`, and `event_type`;
- `destination` (`KAFKA_ONLINE` or `SQS_SAGA`);
- `partition_key` and serialized `payload`;
- `status` (`PENDING`, `IN_FLIGHT`, `DELIVERED`, or `DEAD`);
- `created_at`, `next_attempt_at`, and optional `broker_acknowledged_at`;
- `attempt_count`, optional `last_error`;
- optional `lease_owner` and `lease_expires_at`; and
- `version` for optimistic updates.

Indexes support `(status, next_attempt_at, created_at)`, expired lease recovery, aggregate lookup, and acknowledgement-time reconciliation. Duplicate inserts with the same event ID return the existing record and never enqueue a second logical event.

### `saga_instance`

Each row contains the saga ID, saga type, correlation ID, payload, current status and step, timestamps, and optimistic-lock version. The MySQL implementation replaces the in-memory store in durable deployments; the in-memory implementation remains available for unit tests and local demonstrations.

The saga repository exposes a transaction boundary that conditionally updates the saga version and inserts its transition outbox row. A transaction rollback removes both changes. Direct state-then-publish behavior is not used by the production configuration.

## API Event Acceptance

Online event endpoints validate and serialize an event, then synchronously insert it into `event_outbox`. The request is successful only after the MySQL commit completes. Database failure returns a service error and must not return an acceptance token.

The event ID is the idempotency key. Repeating a request with the same event ID and identical immutable content returns the existing acceptance result. Reusing an event ID with different content returns a conflict.

The legacy in-memory asynchronous publisher remains temporarily observable during migration, but durable endpoints do not route accepted events through it.

## Outbox Relay

Workers claim configurable batches using `SELECT ... FOR UPDATE SKIP LOCKED`. Claiming assigns a worker ID and a short lease, increments the attempt count, and changes the row to `IN_FLIGHT`. An expired lease is reclaimable after a worker crash.

The relay waits for the broker acknowledgement before marking a row `DELIVERED`. Failure schedules bounded exponential backoff with jitter. After a configurable maximum attempt count, the row becomes `DEAD`; dead rows remain queryable and visible to metrics but are not retried automatically.

Kafka and SQS share claiming and lifecycle code but use destination-specific adapters. SQS messages carry the deterministic event ID as an attribute. Kafka records use the outbox partition key.

## Kafka Reliability Contract

Kafka producers set:

- `enable.idempotence=true`;
- `acks=all`;
- an explicit retry count high enough that `delivery.timeout.ms` is the effective bound;
- explicit `delivery.timeout.ms`, `request.timeout.ms`, and `linger.ms`; and
- `max.in.flight.requests.per.connection=5`.

Delivery is considered successful only in the producer callback. Producer idempotence prevents duplicates caused by producer retries within one producer session; stable event IDs provide end-to-end consumer deduplication across sessions and relay retries.

## Consistency Tokens and Primary Reads

An acceptance response contains an opaque token with the event UUID, subject user ID, issued-at time, and 24-hour expiry. The token is authenticated with HMAC using a rotatable server secret and does not contain mutable state.

A recommendation request may submit the token. The service validates the signature, expiry, and subject binding, then checks Redis lineage through the primary connection rather than `executeRead` and bypasses JVM feature caches. It waits for at most two seconds using bounded polling.

Outcomes are:

- lineage present: read current features from the primary and serve normally;
- valid token not yet applied: `202 Accepted` and `Retry-After: 1`;
- malformed or invalid signature: `400 Bad Request`;
- expired token: `409 Conflict`; or
- token subject does not match the requested user: `403 Forbidden`.

Requests without a consistency token retain the existing replica and cache path.

## Redis Version and Atomic Top-K Contract

Every lineage-aware feature stores the applied event ID, event timestamp, and logical version. Ordering uses the tuple `(eventTimeMillis, eventId)` with event IDs compared lexicographically. An incoming tuple replaces stored data only when it is strictly greater. Replaying an identical tuple is a no-op, and equal timestamps resolve deterministically.

One Redis Lua invocation updates the canonical Top-K sorted set, its canonical version metadata, the derived hot-movies sorted set and metadata, and the serialized trend feature and metadata. The invocation either performs every related update or none. All keys use a shared Redis Cluster hash tag so the script remains single-slot compatible.

Lineage keys retain enough event history for the 24-hour reconciliation window plus an operational safety margin.

## Metrics

Micrometer/Prometheus exposes:

- `outbox_delivery_lag_seconds`, measured from row creation to broker acknowledgement;
- `outbox_pending_events`;
- `outbox_delivery_failures_total`;
- `async_events_dropped_total` for remaining legacy publisher traffic;
- `redis_replica_lag_seconds`;
- bounded aggregate Redis feature-version gauges without user or event labels;
- consistency-token validation, wait-success, wait-timeout, and wait-duration metrics; and
- reconciliation scanned, missing, republished, repaired, and failed counters.

Replica lag is measured by periodically writing a timestamped monotonic probe to the primary and reading it through the selected replica. Probe failure reports an unknown/unavailable state rather than a zero value.

## Reconciliation Job

A standalone Java command runs as a Kubernetes CronJob. By default it examines delivered `KAFKA_ONLINE` outbox rows created during the previous 24 hours, with configurable time bounds and maximum batch size.

For each event, it queries the relevant Redis lineage through the primary. Missing lineage causes the same outbox event to be republished with its original event ID and partition key. The job uses database leases so overlapping CronJob executions cannot repair the same event concurrently. It does not automatically retry `DEAD` rows.

Reconciliation distinguishes republished events from repaired events: a republish is attempted immediately, while repair is confirmed only in a later scan after Redis lineage appears.

## Failure Semantics

- MySQL unavailable during event acceptance: reject the request; do not claim acceptance.
- Kafka or SQS unavailable: retain pending or retryable outbox rows.
- Relay crash after broker acknowledgement but before marking delivery: publish again; downstream idempotency absorbs the duplicate.
- Poison payload: transition to `DEAD` after the configured ceiling.
- Redis replica lag: ordinary reads may remain stale; token reads use the primary.
- Redis unavailable during a token read: return a retryable service response rather than silently satisfying read-your-writes from stale cache.
- Reconciliation overlap: leases prevent concurrent repairs.

## Deployment and Migration

1. Apply Flyway migrations for outbox and saga tables.
2. Deploy metrics and relay workers without routing API traffic to the outbox.
3. Enable durable API event acceptance and consistency-token responses.
4. Enable MySQL saga state and transactional transition enqueueing.
5. Deploy atomic Redis scripts and lineage metadata before enabling token waits.
6. Deploy the reconciliation CronJob in report-only mode, then enable repair.
7. Alert on pending age, dead rows, delivery failures, dropped legacy events, replica lag, and reconciliation misses.
8. Remove the legacy in-memory API queue from durable production paths after its dropped and published counters remain zero for an agreed observation period.

Rollback keeps the schema intact. Relay and reconciliation workers can be stopped safely; pending rows remain durable for a later restart.

## Testing

Unit tests cover token signing and validation, deterministic version ordering, retry scheduling, row leases, destination adapters, and metric updates.

MySQL integration tests verify atomic saga-state/outbox commits, rollback, idempotent event insertion, optimistic locking, concurrent `SKIP LOCKED` claims, and expired lease recovery.

Kafka tests verify producer configuration and acknowledgement-driven delivery state. Flink/Redis tests verify atomic related Top-K updates, replay idempotency, and equal-timestamp tie-breaking.

API tests verify that acceptance follows the MySQL commit, duplicate event IDs are idempotent, conflicting reuse is rejected, primary reads bypass caches, successful token waits serve current state, and two-second timeouts return `202`.

Reconciliation tests cover bounded 24-hour scans, missing lineage, duplicate-safe republishing, repair confirmation, dead-event exclusion, and overlapping worker leases.

Docker-tagged end-to-end tests exercise MySQL to relay to Kafka to Flink to Redis and finally a successful consistency-token read.

## Acceptance Criteria

- No accepted durable API event can be lost by an application or broker process crash.
- Saga state and its transition event are committed atomically in production.
- Kafka producer retries are idempotent and bounded by explicit delivery timeouts.
- All requested consistency metrics are scrapeable without unbounded labels.
- Token-bearing reads either observe the accepted event within two seconds or return `202`.
- Related Top-K representations cannot diverge from a partial Redis write.
- Equal event timestamps produce the same final Redis value regardless of arrival order.
- Reconciliation detects and safely republishes missing lineage within the configured window.
