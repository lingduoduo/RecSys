# Runbook: Serving Data Freshness SLOs

Online recommendations are built from data that arrives through a pipeline nobody watches
in real time: Kafka → Flink → Redis for the feature view, and MySQL outbox → relay → Kafka
for durable event delivery. This runbook covers the five alerts that watch the *data* at
those two boundaries, and how to tell a genuinely stale feature view apart from a probe
that simply stopped reporting.

Design: `docs/superpowers/specs/2026-08-04-serving-data-freshness-slos-design.md`.
Alert definitions: [`k8s/base/prometheus-rules.yaml`](../../k8s/base/prometheus-rules.yaml),
group `recsys.data`.

> These are **internal SLOs, not contractual customer SLAs.** They exist to make degradation
> visible to operators. Nothing here is promised to a client.

> Related: [Durable eventual consistency](durable-eventual-consistency.md) explains the outbox,
> relay, and retry model these delivery alerts observe;
> [21_Observability](../system_design/21_Observability.md) is the full metric and alert
> inventory.

## The objectives

| SLO | Signal | Warning | Critical | Hold |
|---|---|---|---|---|
| The online feature view is under 60 s old | `redis_feature_version_age_seconds` | `> 60` | `> 300` | 5 m each |
| Feature freshness is actually being measured | `redis_feature_version_sample_available` | `== 0` | — | 5 m |
| `kafka_online` events deliver within 30 s | `outbox_delivery_lag_seconds_max` | `> 30` | — | 10 m |
| `kafka_online` delivery is not failing continuously | `outbox_delivery_failures_total` | `increase(…[5m]) > 0` | — | 10 m |

The 60-second warning is not arbitrary: it is the entire stale-on-error budget
`OnlineFeatureStore` is allowed to serve within. Breaching it for five minutes means serving
has been outside its own documented contract for five minutes. The 300-second critical means
the feature view has effectively stopped advancing.

## Before diagnosing: is the signal real?

Every one of these alerts reads a series from a process that must actually be scraped. Check
that first — an absent target produces no series, and no series produces no alert.

```bash
# Prometheus knows about all five targets and they are up.
# (RecsysTargetDown covers a target that stopped answering; it cannot cover one Prometheus
# never learned about, which is what ScrapeTargetManifestTest guards instead.)
up{namespace="recsys"}

# The relay in particular — it only became scrapeable in 2026-08. If this is empty,
# the two outbox alerts below are inert no matter what the relay is doing.
up{namespace="recsys", job="recsys-outbox-relay"}
```

```bash
kubectl -n recsys get svc,endpoints recsys-outbox-relay
kubectl -n recsys get servicemonitor recsys-outbox-relay

# Read the exposition directly, bypassing Prometheus entirely.
kubectl -n recsys port-forward deploy/recsys-outbox-relay 7020:7020
curl -s localhost:7020/metrics | grep -E 'outbox_(delivery|pending)'
```

Note the relay's `NetworkPolicy` admits ingress **only** from the `monitoring` namespace's
Prometheus pods. A port-forward tunnels through the API server rather than the pod network, so
it works regardless; a `curl` from another pod in `recsys` will not, and that is intended.

## The trap this whole runbook exists for

`redis_feature_version_age_seconds` initializes to **0** and only moves when a sample
succeeds. It is deliberately **not** cleared when a sample fails — the last known-good age is
the only clue an operator has about how stale the last observable view was.

Both properties mean the age gauge alone cannot be trusted:

- A process that has **never** sampled reads `0` — indistinguishable from perfectly fresh.
- A sampler that **stops** working freezes the age at whatever it last read. If that was 8 s,
  the age reads 8 s forever and `OnlineFeatureDataStale` can never fire, no matter how old
  the data actually gets.

`redis_feature_version_sample_available` is the companion that separates these. **When
`OnlineFeatureVersionSampleUnavailable` is firing, treat the age gauge as meaningless** — not
as evidence of freshness. Read them together, always:

```promql
redis_feature_version_age_seconds{job="recsys-online-serving"}
redis_feature_version_sample_available{job="recsys-online-serving"}
```

The `job` selector is load-bearing. *Every* process that constructs a `ConsistencyMetrics`
registers these gauges — including the outbox relay, which runs no sampler and therefore
reports a permanent age of 0 and availability of 0. The alerts are scoped to
`job="recsys-online-serving"` so a process that was never meant to observe the feature view
cannot page about it. Do not drop the selector when adapting these queries.

## Queries

```promql
# Feature view: age, whether the reading is backed by a real sample, and version spread.
redis_feature_version_age_seconds{job="recsys-online-serving"}
redis_feature_version_sample_available{job="recsys-online-serving"}
redis_feature_version_max{job="recsys-online-serving"} - redis_feature_version_min{job="recsys-online-serving"}

# Durable delivery. _max, not the bare meter name: Micrometer exposes a Timer as
# _count/_sum/_max, and `outbox_delivery_lag_seconds` on its own matches no series at all.
outbox_delivery_lag_seconds_max{destination="kafka_online"}
increase(outbox_delivery_failures_total{destination="kafka_online"}[5m])
rate(outbox_delivery_lag_seconds_sum[5m]) / rate(outbox_delivery_lag_seconds_count[5m])

# Backlog and replica lag — different failure modes, complementary to the above.
outbox_pending_events
outbox_in_flight_events
redis_replica_lag_seconds
```

A wide `redis_feature_version_max - redis_feature_version_min` spread means some feature keys
are advancing while others are stuck — typically one Flink subtask or one Kafka partition is
behind rather than the whole job. The age gauge alone will not show that, because it is
computed from the newest version only.

## Alert-by-alert response

### `OnlineFeatureDataStale` / `OnlineFeatureDataCriticallyStale`

The feature view is advancing too slowly, or has stopped. Work outward from the writer:

1. **Flink first** — this job is the only writer of `*:updated_at`.
   ```bash
   kubectl -n recsys get pods -l app=flink-taskmanager
   ```
   In the Flink UI: is the `OnlineFeatureStreamingJob` `RUNNING`, or restarting in a loop?
   Check checkpoint success and duration, and the watermark on the source operator. A job
   that is `RUNNING` but not checkpointing is the common case for a frozen feature view.
2. **Kafka next** — consumer lag on the job's source topics. Growing lag with a healthy job
   means the job cannot keep up; flat lag with a stalled job means the job is the problem.
3. **Redis last** — confirm the keys exist and are advancing:
   ```bash
   kubectl -n recsys exec -it deploy/redis -- redis-cli --scan --pattern '*:updated_at' | head
   kubectl -n recsys exec -it deploy/redis -- redis-cli get <one-key>
   ```
   Compare the value (epoch milliseconds) against now. If Redis holds fresh values while the
   gauge reads stale, suspect the sampler, not the pipeline — see the next alert.
4. **Logs** — online-serving stdout, or Splunk if `SPLUNK_HEC_TOKEN` is set. Splunk delivery
   is at-most-once, so a search there is a **lower bound** on what was logged; absence of an
   error in Splunk is not evidence it did not happen.

**Mitigation.** Serving continues on bounded stale data by design — it does not error. The
recovery action is to restore the writer (restart the Flink job from its last checkpoint),
not to touch Redis. Do **not** delete feature keys to "force a refresh": that removes the
stale-but-usable view serving is currently degrading gracefully onto, and turns a degradation
into an outage.

### `OnlineFeatureVersionSampleUnavailable`

No sample has succeeded for five minutes. The freshness reading beside it is frozen and must
not be read as healthy.

Three causes, in order of likelihood:

1. **Redis is unreachable from online serving.** Cross-check `RedisCacheUnavailable` and the
   egress rule for `recsys-online-serving` in
   [`k8s/base/network-policy.yaml`](../../k8s/base/network-policy.yaml). If both alerts are
   firing, this is a Redis connectivity incident, not a data-freshness one.
2. **No `*:updated_at` keys exist at all.** A scan that matches nothing marks the sample
   unavailable rather than reporting an age — correct, since there is no feature view to age.
   Expected on a freshly bootstrapped Redis (DR region, local dev) before the Flink job has
   written anything. Confirm with the `--scan` command above.
3. **The sampler never started.** It is only started when online serving has a Redis executor
   configured; a pod that came up without one samples nothing for its whole lifetime. Check
   online-serving startup logs.

The sampler is scheduled with a fixed delay (`REDIS_FEATURE_VERSION_SAMPLE_SECONDS`, default
30 s) and swallows failures per-attempt by design, so a single failed scrape is invisible here
— only a sustained inability to sample trips the 5-minute hold.

### `OutboxDeliveryLatencyHigh`

Events accepted by the API are reaching Kafka well outside the 30-second window.

1. `outbox_pending_events` — is the backlog also growing? If so this is throughput, and
   `OutboxBacklogGrowing` should be firing too; the relay is not keeping up.
2. Relay logs for slow or timing-out sends:
   ```bash
   kubectl -n recsys logs deploy/recsys-outbox-relay --tail=200
   ```
3. Kafka broker reachability and partition leadership from the relay's vantage point. A
   partition whose leader is being re-elected shows up as latency, not failure.
4. MySQL — the relay leases outbox rows with `SKIP LOCKED`; a slow or contended outbox table
   inflates measured delivery lag, since the lag is measured from event creation, not from
   send time.

**Note what this alert cannot see.** `outbox_delivery_lag_seconds_max` is a *decaying window*
max (Micrometer's default is a two-minute window), so it reflects recent deliveries only and
falls back toward 0 when deliveries stop **entirely**. A completely stalled relay therefore
does not raise this alert — it raises `OutboxBacklogGrowing` instead. The two are
complementary and neither replaces the other.

### `OutboxDeliveryFailuresSustained`

Delivery attempts have been failing without a break for ten minutes.

`outbox_delivery_failures_total` increments once per failed **attempt**, and the relay
retries. A short burst of failures is routine and is deliberately not alertable — this fires
only when failures keep arriving across the whole ten-minute hold.

1. Relay logs for the failure class — authentication, unknown topic, timeout, serialization.
2. Kafka reachability from the relay pod, and whether the destination topic still exists with
   the expected partition count (see
   [Kafka partition cutover](kafka-partition-cutover.md) if a cutover is in progress).
3. Check for events that exhausted the retry policy and were marked **dead** — those will not
   be retried again and need the reconciliation path in
   [Durable eventual consistency](durable-eventual-consistency.md).

**Events are not lost while this fires.** They remain durable in the MySQL outbox; the
consistency window simply widens until delivery recovers. Do not clear the outbox table to
resolve the alert.

#### Why the lookback is shorter than the hold

This alert uses a 5-minute `increase()` window under a 10-minute `for:`, which is the inverse
of the relationship §4 of [21_Observability](../system_design/21_Observability.md) warns about
for burst alerts — and deliberately so:

- A burst alert wants `for:` **well below** the range window, or an isolated spike ages out of
  the range before the hold is satisfied and the alert can never fire.
- This alert wants the opposite. It is named `…Sustained` because a single retryable failure
  is not an incident. A window as long as the hold keeps a *finished* burst positive for long
  enough to satisfy it: measured with `promtool`, a four-attempt burst under a 10-minute
  window fires a spurious page at t=11 m. A 5-minute window cannot span a 10-minute hold, so
  only failures that keep arriving can fire it.

Both directions are pinned by cases in `k8s/base/prometheus-rules.test.yaml` that were
confirmed to turn red when the window is mutated. If you change either number, re-run them —
`promtool check rules` does **not** check this relationship.

## Limitations

- **This repository installs neither Prometheus Operator nor Alertmanager.**
  `prometheus-rules.yaml` and `servicemonitor.yaml` are CRs that do nothing on their own. If
  your cluster has no Operator running, these alerts are inert files — committed, unit-tested,
  and never evaluated. Verify with `kubectl get prometheusrules -n recsys` and by finding the
  rules loaded in the Prometheus UI, not by their presence in git.
- **Splunk is a lower bound.** Log shipping is at-most-once by design, so a search may be
  missing events that were genuinely logged. Use stdout for authoritative per-pod detail. See
  [Splunk HEC logging](splunk-hec-logging.md).
- **Alert coverage stops at the boundaries listed here.** These alerts observe the feature
  view in Redis and the outbox relay's delivery. They say nothing about whether the *contents*
  of a feature are correct — only about how old they are and whether events are moving.
- **Freshness is sampled, not exact.** The age comes from a bounded scan
  (`REDIS_FEATURE_VERSION_SAMPLE_LIMIT`, default 1000 keys) of `*:updated_at`, taken every 30
  seconds. It is a good estimate of the newest feature version, not a guarantee about every
  key in the keyspace.
