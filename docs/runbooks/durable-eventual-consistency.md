# Durable Eventual Consistency — Migration & Operations Runbook

Covers the transactional outbox, durable saga state, outbox relay, consistency tokens, atomic
Redis Top-K updates, consistency metrics, and the reconciliation CronJob. Design:
`docs/superpowers/specs/2026-07-18-durable-eventual-consistency-design.md`. Plan:
`docs/superpowers/plans/2026-07-18-durable-eventual-consistency.md`.

The whole subsystem is **off by default** (`ONLINE_DURABLE_EVENTS_ENABLED=false`,
`MYSQL_ENABLED=false`). Tokenless requests always retain the legacy replica-and-cache path.

## Components

| Component | Runs as | Entry point |
|---|---|---|
| Durable API acceptance + consistency tokens | Online serving (7010) | `OnlinePredictionServer` |
| Outbox relay | Own Deployment (`recsys-outbox-relay`, 7020) | `OutboxRelayCommand` |
| Reconciliation | CronJob (`recsys-outbox-reconciliation`, hourly) | `ReconciliationCommand` |
| Atomic Top-K apply + lineage | Flink | `OnlineFeatureStreamingJob` |

## Required configuration

Non-secret keys live in `k8s/base/configmap.yaml`; **secrets live in the `recsys-secrets` Secret**
(referenced `optional: true` by the relay Deployment and reconciliation CronJob) and are never put
in the ConfigMap:

- `MYSQL_PASSWORD`
- `ONLINE_CONSISTENCY_TOKEN_SECRET` — **must be ≥ 32 UTF-8 bytes**; startup fails otherwise.

`MYSQL_URL` must carry `sslMode=VERIFY_IDENTITY` — spelled exactly that way, since Connector/J
property names are case-sensitive and a wrong-case key is dropped silently — and must not carry
the deprecated `useSSL`. Loopback hosts (`localhost`, `127.0.0.1`, `[::1]`) are exempt. Any
service that builds MySQL settings refuses to start otherwise; Connector/J's `PREFERRED` default
would otherwise fall back to plaintext without an error.

Key env vars: `MYSQL_ENABLED`, `MYSQL_URL`, `MYSQL_USER`; `ONLINE_DURABLE_EVENTS_ENABLED`;
`OUTBOX_KAFKA_BOOTSTRAP_SERVERS`, `OUTBOX_KAFKA_ONLINE_TOPIC`, `OUTBOX_DELIVERY_DEADLINE_MS`;
`OUTBOX_RELAY_*` (relay tuning; `OUTBOX_RELAY_POLL_MS` is the claim cadence, the per-send /
relay-cycle deadline is `OUTBOX_DELIVERY_DEADLINE_MS`); `RECONCILIATION_WINDOW_HOURS`,
`RECONCILIATION_MAX_BATCH`, `RECONCILIATION_REPAIR`, `RECONCILIATION_LEASE_SECONDS`.

## Rollout (staged — do not skip ordering)

1. **Migrate schema.** Apply Flyway migrations for `event_outbox` and `saga_instance`
   (`V2__create_event_outbox_and_sagas.sql` and later). Schema is additive and safe to apply while
   the feature is off.
2. **Deploy metrics + relay, no traffic.** Set `MYSQL_ENABLED=true` — and confirm `MYSQL_URL`
   sets `sslMode=VERIFY_IDENTITY` first, or every workload that builds MySQL settings, including
   6010, refuses to start — then roll out
   `recsys-outbox-relay`. With no producers yet the relay idles; confirm `/health/ready` is green
   and `/metrics` scrapes (`outbox_pending_events` ≈ 0).
3. **Enable durable API acceptance.** Set `ONLINE_DURABLE_EVENTS_ENABLED=true` and provide
   `ONLINE_CONSISTENCY_TOKEN_SECRET`. Accepted events now commit to MySQL before the response and
   return `X-Consistency-Token`. Watch `outbox_delivery_lag_seconds` and `outbox_pending_events`.
4. **Enable durable saga state.** Route production saga orchestrators to `MySqlSagaStateStore` so a
   transition and its `SQS_SAGA` outbox row commit in one transaction. Verify no double-publishing.
5. **Deploy atomic Redis script + lineage before token waits.** Ensure the Flink job writes the
   single-slot atomic Top-K + `lineage:event:<id>` sets and version metadata. Only then rely on
   token reads (they check primary lineage and bypass caches).
6. **Reconciliation report-only → repair.** The CronJob ships with `RECONCILIATION_REPAIR=false`.
   After the report-only `reconciliation_events_total{outcome="missing"}` counter has been observed
   for the agreed window, set `RECONCILIATION_REPAIR=true`.
7. **Alerts.** Page/warn on: pending-event age, `outbox` `DEAD` rows,
   `outbox_delivery_failures_total`, `async_events_dropped_total` (legacy publisher),
   `redis_replica_lag_seconds`, and `reconciliation_events_total{outcome="missing"}`.
8. **Retire legacy queue.** Remove the in-memory API queue from durable paths only after its
   dropped and published counters stay zero for the agreed observation period.

## Consistency token read outcomes

`200` lineage present (served from primary, caches bypassed) · `202` + `Retry-After: 1` valid token
not yet applied (≤ 2 s wait) · `400` malformed/invalid signature · `409` expired · `403` subject
mismatch · `503` primary Redis unavailable (never served from stale cache).

## Delivery semantics

At-least-once. Duplicates are absorbed by stable event IDs, keyed Kafka records, deterministic
`(eventTimeMillis, eventId)` version comparison in the Flink sink, and idempotent consumers. Kafka
producers use `enable.idempotence=true`, `acks=all`, explicit retries, and explicit delivery /
request timeouts. A relay crash after broker ack but before marking `DELIVERED` re-publishes on the
next claim; downstream idempotency absorbs it.

## Dead-letter operations

Rows reach `DEAD` after `OUTBOX_RELAY_MAX_ATTEMPTS`. Dead rows are never retried automatically and
are never auto-repaired by reconciliation. To reprocess after fixing the root cause, reset the row
to `PENDING` (clear `lease_owner`/`lease_expires_at`, set `next_attempt_at = now`, `attempt_count = 0`)
and let the relay reclaim it. Investigate the persisted `last_error` (truncated to 2,000 chars).

## Reconciliation

Hourly CronJob (`concurrencyPolicy: Forbid`). Each run scans delivered `KAFKA_ONLINE` rows from the
previous `RECONCILIATION_WINDOW_HOURS`, checks primary Redis lineage, and — when repair is enabled —
republishes the original event ID + partition key for missing lineage under a per-event DB lease so
overlapping runs cannot republish the same event. Republish is counted immediately; **repair** is
confirmed only on a later run once lineage appears. `DEAD` rows are excluded.

## Rollback

Rollback keeps the schema intact. Relay and reconciliation workers stop safely; **pending rows stay
durable** for a later restart. To pause without data loss: set `ONLINE_DURABLE_EVENTS_ENABLED=false`
(API returns to legacy acceptance) and/or scale `recsys-outbox-relay` to 0. Do not drop the outbox
tables while any `PENDING`/`IN_FLIGHT` rows remain.
