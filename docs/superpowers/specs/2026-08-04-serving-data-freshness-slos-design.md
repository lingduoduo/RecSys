# Serving data freshness SLOs design

## Goal

Turn the repository's existing Redis feature-version and durable-delivery metrics into
explicit, tested service-level objectives for the data consumed by online recommendation
paths. Improve the monitoring documentation so an operator can distinguish stale data,
delivery degradation, missing telemetry, and an unavailable dependency.

These are internal SLOs, not contractual customer SLAs.

## Why this scope fits this repository

This repository does not own a BigQuery endpoint or a scheduled partitioned-table
pipeline. A generic `storageUriPattern` checker would add an unused service and cloud
dependency. The data boundaries that matter here are already consumer-visible:

- Kafka and the Flink online-feature job publish versioned state into Redis;
- online serving consumes that Redis state;
- the transactional outbox measures pending work, delivery lag, and delivery failures;
- Redis replica lag measures whether serving reads trail accepted writes.

The implementation therefore adds SLO evaluation around metrics already emitted by the
application. It does not add BigQuery code, a generic endpoint YAML schema, a dashboard,
or a new monitoring service.

## Existing signals

`ConsistencyMetrics` already registers:

- `redis_feature_version_age_seconds`;
- `redis_feature_version_min` and `redis_feature_version_max`;
- `outbox_delivery_lag_seconds`, a Micrometer `Timer` tagged by `destination`;
- `outbox_delivery_failures_total`, tagged by `destination`;
- `outbox_pending_events` and `outbox_in_flight_events`; and
- `redis_replica_lag_seconds` and `redis_replica_lag_available`.

The current rules cover a growing outbox backlog and Redis replica lag, but do not enforce
feature freshness, delivery latency, or sustained delivery failures. No new application
instrumentation is planned unless a focused exposition test proves that an existing
signal cannot express the SLO safely.

## SLOs and alerts

### Online feature freshness

The documented serving contract allows an `OnlineFeatureStore` value to be served stale
for at most 60 seconds when Redis refresh fails. Monitoring uses that same boundary:

- **SLO:** the newest observed Redis feature version is no more than 60 seconds old.
- **Warning:** `redis_feature_version_age_seconds > 60` continuously for 5 minutes.
- **Critical:** `redis_feature_version_age_seconds > 300` continuously for 5 minutes.

The warning detects sustained breach of the intended stale-on-error window without paging
on one delayed sample. The critical rule means the online feature view has remained five
minutes old, far outside the serving contract.

The metric initializes to zero and is updated only when the feature-version sampler
successfully observes Redis. A zero value before the first successful sample must not be
claimed as evidence that data is fresh. Before implementing the alert, the plan must trace
the sampler's availability/lifecycle signal. If the repository has no proven companion
signal for "sample has succeeded," add the smallest bounded-cardinality availability
gauge and alert branch needed to distinguish `never sampled` from a real zero-second age.

### Durable online-event delivery

- **SLO:** successfully delivered `kafka_online` outbox events complete within 30 seconds.
- **Latency warning:** the proven Prometheus series emitted by the
  `outbox_delivery_lag_seconds` timer exceeds 30 seconds continuously for 10 minutes.
- **Failure warning:** `increase(outbox_delivery_failures_total{destination="kafka_online"}[10m])`
  remains above zero for a `for: 10m` window.

Micrometer timer exposition varies with registry configuration. The implementation must
first add a focused test that scrapes a real `PrometheusMeterRegistry`, records a known
delivery duration, and pins the exact emitted series name and units. The Prometheus rule
must use that proven name; it must not guess `_max`, `_sum`, `_count`, or histogram names.
If the existing timer exposes no stable recent-latency signal suitable for alerting, add a
dedicated gauge updated alongside the timer rather than deriving a misleading rule from a
cumulative sum.

The existing `OutboxBacklogGrowing` and `RedisReplicaLagHigh` alerts remain unchanged.
They describe different failure modes and complement the new rules.

## Missing and invalid telemetry

PromQL comparisons against an absent series, `NaN`, or an initialization sentinel can
silently evaluate to no alert. Each new rule must explicitly match the actual behavior of
its producer:

- feature freshness must distinguish a successful zero-age sample from no sample yet;
- delivery latency must not page when there have been no delivered events;
- a missing scrape is handled by `RecsysTargetDown`, not duplicated in each data alert;
- a failed data probe must never be interpreted as fresh data; and
- labels stay bounded to the existing `job`, `instance`, and `destination` dimensions.

No user ID, event ID, model version, Redis key, exception message, or raw endpoint becomes
a metric label.

## Prometheus rule tests

Every added alert receives `promtool` coverage in
`k8s/base/prometheus-rules.test.yaml`:

- a series that crosses the threshold and remains there for the full `for:` duration;
- a near-miss at or below the threshold that must not fire;
- a transition that proves the `for:` duration suppresses a brief spike;
- an initialization/no-sample or no-traffic case where that state is meaningful; and
- exact expected severity labels and operational annotations.

The existing workflow extracts `.spec` from the `PrometheusRule` CRD with `yq` and runs
`promtool test rules`. The new cases use that same path. A Java exposition test pins any
Micrometer timer/gauge name used by PromQL so a library upgrade cannot silently disconnect
the alert from its producer.

## Monitoring runbook and system documentation

Add `docs/runbooks/serving-data-freshness.md` and link it from the README runbook index.
The runbook will include:

- the SLO table, thresholds, and why warning and critical windows differ;
- PromQL queries for current feature age, version spread, outbox delivery latency,
  failures, pending work, and replica lag;
- diagnosis order for Kafka producer/consumer health, Flink job state, checkpoints and
  watermarks, Redis feature versions, and outbox relay state;
- how to distinguish stale data from an unavailable scrape or a never-successful sample;
- rollback/mitigation guidance based on repository behavior, including serving bounded
  stale data, restoring the streaming job, draining the outbox, and checking stdout or
  Splunk for specific failures; and
- the explicit limitation that the repository does not install Prometheus Operator or
  Alertmanager, so committed rules are inert unless the environment supplies them.

Update `docs/system_design/21_Observability.md` with the new data-freshness SLOs and alert
inventory. Keep the full incident procedure in the runbook rather than duplicating it in
the system-design document.

## Files and boundaries

Expected modifications:

- `k8s/base/prometheus-rules.yaml` — new SLO alerts;
- `k8s/base/prometheus-rules.test.yaml` — firing and non-firing rule cases;
- `src/test/java/com/recsys/metrics/ConsistencyMetricsTest.java` or a focused new metrics
  exposition test — exact Prometheus timer/gauge contract;
- `src/main/java/com/recsys/metrics/ConsistencyMetrics.java` only if a proven availability
  or recent-latency signal is missing;
- the sampler wiring only if needed to update an added availability gauge;
- `docs/runbooks/serving-data-freshness.md` — operational response;
- `docs/system_design/21_Observability.md` — SLO and alert inventory; and
- `README.md` plus `DocumentationIndexTest` if required by its runbook-index contract.

Do not modify the serving request path, Redis freshness windows, outbox retry policy,
Flink processing semantics, or Kubernetes deployment topology as part of this change.

## Verification

- Run focused Java metric tests, including the Prometheus exposition contract.
- Generate the bare rule file and run every `promtool` unit test.
- Run `ScrapeTargetManifestTest`, `DocumentationIndexTest`, and any documentation contract
  test affected by the new runbook link.
- Run `kubectl kustomize k8s/base` to ensure the rule CRD still renders.
- Run `git diff --check`.
- Run the Maven suite excluding `load,docker`; the clean-checkout baseline currently has
  two known `ModelArtifactLocatorTest` errors because
  `artifacts/pyspark/als_model_metadata.json` is absent. No additional failure is accepted.

## Success criteria

- Operators receive a tested warning when online features remain older than 60 seconds
  and a critical alert when they remain older than 300 seconds.
- Operators receive tested warnings for sustained `kafka_online` outbox latency above 30
  seconds and sustained delivery failures.
- A missing or never-successful measurement cannot look healthy.
- Every PromQL metric name is pinned to real application exposition.
- The runbook provides a direct path from alert to Kafka, Flink, Redis, outbox, and log
  evidence without implying that Prometheus or Alertmanager is deployed by this repo.
