# Observability in Recsys-Backend-Service

An investigation of how this system answers two different questions about itself: what
happened in a specific request, and whether the system as a whole is healthy. Phase 1
(2026-07-31, `feat/splunk-hec-log-shipping`) shipped the first. Phase 2 (this change)
ships the second, and bridges Phase 1's own delivery counters into it so log-shipping
loss stops being something you find only by reading stdout.

## 1. Two questions, two tools, no derivation between them

**Splunk answers "what happened in this request?"** A specific `traceId`, a stack trace,
one user's malformed cursor, the exact HEC rejection body Splunk sent back. The data is
per-event, high-cardinality (a `userId` or a raw exception message is a fine log field
and a forbidden metric label), and delivered **at-most-once** — `SplunkHecAppender` drops
on a full queue or a failed POST rather than blocking a serving thread. A Splunk search
is a lower bound on what happened, never a complete record. See
[`docs/runbooks/splunk-hec-logging.md`](../runbooks/splunk-hec-logging.md).

**Prometheus answers "is the system healthy right now?"** Rates, saturation, latency
percentiles, error ratios — low-cardinality, scraped on a 15 s interval, retained as a
time series so `rate()` and `increase()` mean something. It cannot tell you which user
hit the bug; it can tell you that 12% of requests are failing and have been for six
minutes.

**Neither is derived from the other, and that is deliberate, not an oversight.** No
metric in this system is computed by parsing a log line — the Splunk pipeline has no log
processor, aggregator, or Prometheus exporter reading from `logs/*.log` or from Splunk's
index. Conversely no log line exists to represent a metric ("failure rate is now 0.14")
— gauges and counters are read straight off the same `AtomicLong`/`LongAdder` fields the
request path already updates, over Micrometer, with no logging step in between. The one
apparent exception proves the rule: `SplunkHecMetrics` publishes the *appender's own*
delivery counters as gauges, but that is metrics about the logging pipeline's mechanics
(how many events were dropped), not metrics derived from the *content* of any log event.

The operational consequence: **alerting reads Prometheus, investigation reads Splunk.**
`k8s/base/prometheus-rules.yaml` never contains a Splunk query, and no alert here fires
on "an ERROR log appeared" — that would require log-derived metrics, which is exactly
what §5 says does not exist. When `RedisCacheUnavailable` fires, the response is to look
at Redis pod status and, if you need the specific failing calls, cross to Splunk with the
service name and time window — not the other way around.

## 2. Phase 1 — logs to Splunk

All four service mains ship structured JSON log events to Splunk's HTTP Event Collector
in addition to console/`logs/*.log`, gated entirely on whether `SPLUNK_HEC_TOKEN` is set
— unset, `SplunkHecAppender` is a no-op and every request path is unaffected. Delivery is
**at-most-once**: a bounded queue (`SPLUNK_HEC_QUEUE_CAPACITY`, default 10,000) drops on
full, and a failed POST is never retried. Console output is the authoritative copy;
Splunk is a convenience index on top of it, not a second system of record.

The full design — the Logback-config constraint that rules out `<springProfile>` tags,
local bring-up (and why it cannot be reproduced on Apple Silicon), EKS enablement, secret
scanning history, and the five delivery outcomes `SplunkHecClient` classifies into — is
in [`docs/runbooks/splunk-hec-logging.md`](../runbooks/splunk-hec-logging.md). This
document does not repeat it; it only picks up where the appender's counters stop being
console-only text and start being Prometheus series (§4 below, `SplunkHecMetrics`
registers five gauges/counters: `splunk_hec_events_sent_total`,
`_dropped_total`, `_failed_total`, `_indeterminate_total`, `splunk_hec_queue_depth`).

**One correction to keep straight**: Phase 1's own runbook already states `traceId` is
populated only by the model service (`TraceIdAspect` is Spring AOP; the three Armeria
mains have no Spring container to weave it into). That limitation is inherited unchanged
into Phase 2 — see §5.

## 3. Phase 2 — metrics to Prometheus

### 3.1 What each service exposes, and what collects it

| Service | Port | Metrics path | `ServiceMonitor` |
|---|---:|---|---|
| RecSys Serving API (`RecSysServer`) | 6010 | `/metrics` | `recsys-catalog-serving` |
| Online Serving (`OnlinePredictionServer`) | 7010 | `/metrics` | `recsys-online-serving` |
| API Gateway (`MicroserviceGatewayServer`) | 8010 | `/metrics` | `recsys-api-gateway` |
| Model Serving (Spring Boot, `ModelApplication`) | 8080 | `/actuator/prometheus` | `recsys-model-serving` |

The three Armeria mains wire a `PrometheusMeterRegistry` directly and mount
`PrometheusExpositionService` at `/metrics` (`RecSysServer.java`, `OnlinePredictionServer.java`,
`MicroserviceGatewayServer.java`). The Spring model service uses Spring Boot Actuator's
own `/actuator/prometheus` endpoint instead — a different path, same Prometheus text
format, same Micrometer registry underneath. All four `ServiceMonitor`s live in
[`k8s/base/servicemonitor.yaml`](../../k8s/base/servicemonitor.yaml), scrape every 15 s
with a 10 s timeout, and select by the Service's `app` label at
`release: kube-prometheus-stack` (the kube-prometheus-stack Helm chart's default
`serviceMonitorSelector` — adjust if your cluster's Prometheus CR uses a different one).

### 3.2 The scrape gap was three layers deep

Before this change, online-serving and the API gateway published Prometheus exposition
that nothing in the cluster could have collected — and each of the three layers below
fails **silently** on its own, which is what let this ship unnoticed:

| Layer | What was missing | Symptom without it |
|---|---|---|
| `ServiceMonitor` | No CR existed for `recsys-online-serving` / `recsys-api-gateway` | Prometheus never learns the target exists — no series, no error, nothing to alert on because there is no `up{job=...}` to be `0` |
| Service `metadata.labels` | The two Services had a pod `selector` but no `metadata.labels` | Even with a `ServiceMonitor` present, `spec.selector.matchLabels` matches the **Service's own labels**, not the Service's pod selector — the easy confusion. A monitor pointed at a label no Service carries matches nothing and scrapes nothing, with no error anywhere |
| `NetworkPolicy` ingress | No rule admitting traffic from the `monitoring` namespace | Prometheus's scrape connection is dropped at the network layer. This looks identical to a slow or hung target from Prometheus's side — it just times out |

Fixing any one of these alone would have changed nothing observable. All three had to
land together: the `ServiceMonitor`s in `servicemonitor.yaml`, the `metadata.labels` on
each Service (`k8s/base/api-gateway.yaml`, `online-serving.yaml`, and the two that were
already correct, `catalog-serving.yaml` / `model-serving.yaml`), and the Prometheus
ingress rule in every service's block of
[`k8s/base/network-policy.yaml`](../../k8s/base/network-policy.yaml). This is the
clearest example in the repo of instrumentation that looked present — metrics were being
computed, the exposition endpoint answered `curl` — and was not actually observable by
anything.

### 3.3 Metric inventory, by subsystem

Every metric below is named at its registration call site; the file is the source of
truth, not this table — verify by reading it, not by trusting the prose.

**Serving** — request-shape and outcome metrics for the online-serving and catalog paths.

| Metric | Registered in |
|---|---|
| `online_serving_qps`, `_failure_rate`, `_rejected_rate`, `_p50_ms`, `_p95_ms`, `_p99_ms` | [`metrics/OnlineServingMetricsService.java`](../../src/main/java/com/recsys/metrics/OnlineServingMetricsService.java) |
| `recsys.recall.degradation.outcomes` (dotted → `recsys_recall_degradation_outcomes_total`, tagged `outcome`) | [`application/retrieval/multichannel/RecallDegradationMetrics.java`](../../src/main/java/com/recsys/application/retrieval/multichannel/RecallDegradationMetrics.java) |
| `recsys.pagination.cursor.rejected` (tagged `reason`), `.cursor.legacy.accepted`, `.cursor.previous_key.verified`, `.page.returned` (tagged `terminal`), `.budget.exhausted` | [`application/pagination/RecommendationPaginationMetrics.java`](../../src/main/java/com/recsys/application/pagination/RecommendationPaginationMetrics.java) |

**Gateway** — service-registry resolution health and origin-secret enforcement.

| Metric | Registered in |
|---|---|
| `gateway_registry_services_total`, `_services_resolved`, `_snapshot_age_seconds`, `_refresh_total`, `_refresh_failures_total` | [`metrics/GatewayRegistryMetrics.java`](../../src/main/java/com/recsys/metrics/GatewayRegistryMetrics.java) |
| `gateway_origin_secret_rejected_total` | [`application/gateway/GatewayOriginSecret.java`](../../src/main/java/com/recsys/application/gateway/GatewayOriginSecret.java) |

**Redis** — cache-tier and replication health, independent of application-side hit rates.

| Metric | Registered in |
|---|---|
| `redis_cache_available`, `_used_memory_bytes`, `_max_memory_bytes`, `_evicted_keys`, `_keyspace_hits`, `_keyspace_misses`, `_evicts_only_volatile_keys`, `redis_keyspace_sampled_keys`, `redis_keyspace_sample_available`, `redis_unexpected_persistent_keys` | [`metrics/RedisCacheMetrics.java`](../../src/main/java/com/recsys/metrics/RedisCacheMetrics.java) |
| `redis_replica_lag_available`, `redis_replica_lag_seconds`, `redis_feature_version_min`, `_max`, `_age_seconds` | [`metrics/ConsistencyMetrics.java`](../../src/main/java/com/recsys/metrics/ConsistencyMetrics.java) |
| `recsys_online_rate_limit_decisions_total` (tagged `source`, `result`) | [`ratelimit/RedisRateLimiter.java`](../../src/main/java/com/recsys/ratelimit/RedisRateLimiter.java) |

**Outbox / consistency** — transactional outbox backlog and durable-delivery bookkeeping,
all in the one class because they share the online server's `ConsistencyMetrics` instance.

| Metric | Registered in |
|---|---|
| `outbox_pending_events`, `outbox_in_flight_events`, `outbox_delivery_lag_seconds` (tagged `destination`), `outbox_delivery_failures_total` (tagged `destination`), `async_events_dropped_total` (tagged `event_type`), `consistency_token_validation_total` (tagged `outcome`), `consistency_wait_total` (tagged `outcome`), `consistency_wait_duration_seconds`, `reconciliation_events_total` (tagged `outcome`) | [`metrics/ConsistencyMetrics.java`](../../src/main/java/com/recsys/metrics/ConsistencyMetrics.java) |

**Inference** — the model-serving (8080) request path.

| Metric | Registered in |
|---|---|
| `recsys.inference.requests` (dotted → `recsys_inference_requests_total`, tagged `result`), `.recent_failure_rate`, `.throughput_per_second` | [`metrics/InferenceMetricsService.java`](../../src/main/java/com/recsys/metrics/InferenceMetricsService.java) |

**Load shedding** — the model-serving semaphore gate.

| Metric | Registered in |
|---|---|
| `recsys.load_shedder.requests` (dotted → `recsys_load_shedder_requests_total`, tagged `result`), `.in_flight_requests`, `.utilization` | [`loadshed/LoadShedder.java`](../../src/main/java/com/recsys/loadshed/LoadShedder.java) |

**Splunk shipping** — the Phase 1 → Phase 2 bridge (§2, §4).

| Metric | Registered in |
|---|---|
| `splunk_hec_events_sent_total`, `_dropped_total`, `_failed_total`, `_indeterminate_total`, `splunk_hec_queue_depth` | [`metrics/SplunkHecMetrics.java`](../../src/main/java/com/recsys/metrics/SplunkHecMetrics.java) |

**Two naming conventions coexist, deliberately.** Most of this system's own metrics
(`gateway_registry_*`, `redis_cache_*`, `outbox_*`, `splunk_hec_*`) are registered with
Prometheus-native `snake_case` names directly. A handful (`recsys.inference.*`,
`recsys.load_shedder.*`, `recsys.recall.degradation.*`, `recsys.pagination.*`) are
registered with dotted Micrometer convention names instead — Micrometer's Prometheus
registry converts the dots to underscores on exposition, so
`recsys.inference.recent_failure_rate` becomes `recsys_inference_recent_failure_rate` in
`/actuator/prometheus`/`/metrics` output and in every alert expression and Grafana query
you would ever write against it. Both forms end up snake_case on the wire; the dotted
form is just the Spring/Micrometer-idiomatic way of writing the same name in Java.

## 4. Alerts

All eight live in
[`k8s/base/prometheus-rules.yaml`](../../k8s/base/prometheus-rules.yaml), one
`PrometheusRule` CR. Every expression was checked against a real metric name in
`src/main/java` before being written — an alert on a metric that is never emitted is
worse than no alert, because it looks like coverage and can never fire.

| Alert | Means | Likely cause | First response |
|---|---|---|---|
| `RecsysTargetDown` | `up{namespace="recsys"} == 0` for 5 m — Prometheus cannot scrape a target at all | Pod crashed, is stuck starting, or its metrics endpoint hung | `kubectl get pods -n recsys`, then the pod's logs for startup errors. **Every other alert in this file is blind for that service until this resolves** — its blind spot is the mirror image of §3.2: `up` only exists for targets Prometheus already knows about, so a `ServiceMonitor` that was never created produces no series to be zero. `ScrapeTargetManifestTest` covers that side; this alert covers a target that existed and stopped answering. |
| `SplunkHecDroppingEvents` | `increase(splunk_hec_events_dropped_total[10m]) > 0` for 2 m — the bounded queue filled and events were discarded | A burst of log volume outran the drain thread, or Splunk itself is slow/down | These log events are gone for good (at-most-once, §2) — check `SPLUNK_HEC_QUEUE_CAPACITY` headroom and whether Splunk is reachable; raise the capacity if this is a recurring burst pattern rather than a one-off. |
| `SplunkHecIndeterminateDelivery` | `increase(splunk_hec_events_indeterminate_total[10m]) > 0` for 2 m — a batch was sent but never acknowledged | Usually a read timeout, or `stop()` running past its 2 s drain budget on shutdown | Delivery is unknown, not lost — check whether the events actually landed in Splunk before assuming loss; if this correlates with deploys, it is probably shutdown timing, not a live outage. |
| `GatewayRegistryStale` | `gateway_registry_snapshot_age_seconds > 120` for 5 m — the gateway is resolving upstreams from an old registry snapshot | Redis is unreachable from the gateway, or `SERVICE_REGISTRY_REFRESH_MS` polling has stalled | Check Redis availability from the gateway pod and `gateway_registry_refresh_failures_total`; the gateway falls back to static routes when a service is unregistered, so this is a staleness warning, not an outage by itself. |
| `OnlineServingShedding` | `online_serving_rejected_rate > 0.1` for 10 m — admission control is rejecting more than 10% of traffic on a sustained basis | Genuine overload, or `ONLINE_MAX_CONCURRENT_REQUESTS` set too low for real traffic | Check `recsys_load_shedder_utilization` and the pod's CPU; this is a partial-degradation warning (90%+ of traffic still succeeds), not a full outage. |
| `RedisCacheUnavailable` | `redis_cache_available == 0` for 5 m — the cache stats probe cannot reach Redis at all | Redis pod down, or a NetworkPolicy egress rule blocking the connection | Check Redis pod status and the relevant service's egress rule in `k8s/base/network-policy.yaml`; serving degrades to stale-or-empty results depending on the path (§1 of [02_Caching](02_Caching.md)) rather than erroring outright. |
| `RedisReplicaLagHigh` | `redis_replica_lag_seconds > 10 or redis_replica_lag_available == 0` for 10 m — replica reads are stale, or the lag probe itself can't tell | Replica pod slow/overloaded, or a network partition to the replica | Check replica pod status and network connectivity between replicas; a read routed to a lagging replica can contradict a write that already succeeded elsewhere. |
| `OutboxBacklogGrowing` | `outbox_pending_events > 1000 and delta(outbox_pending_events[15m]) > 0` for 3 m — more than 1000 events pending **and** still rising | The outbox relay is falling behind its publish target (Kafka or SNS) | Check `outbox_delivery_failures_total` and `outbox_delivery_lag_seconds` to identify which destination is backed up; eventual-consistency windows widen for as long as this holds. |

**Two general traps this file already fell into once, worth naming so nobody repeats
them elsewhere:**

- **`for:` cannot equal the `increase()`/`delta()` range window.** `SplunkHecDroppingEvents`,
  `SplunkHecIndeterminateDelivery`, and `OutboxBacklogGrowing` originally paired a 10–15
  minute `for:` with a range window of the same length. An isolated spike ages out of the
  range and the expression flips back to false *before* `for:`'s sustained-truth
  requirement is satisfied — so the alert can never fire for anything but a continuously
  regenerating condition, which defeats the point of alerting on a burst. The fix was to
  shrink `for:` well below the range (2–3 m against a 10–15 m window), not to widen the
  range further. Anyone adding a new `increase()`/`delta()`-based alert should check this
  relationship explicitly, because `promtool check rules` does not.
- **A gauge that goes `NaN` on failure silently defeats a `>` comparison.**
  `RedisReplicaLagHigh` originally read only `redis_replica_lag_seconds > 10`. When the
  replica probe cannot reach the replica at all, `redis_replica_lag_seconds` reports
  `NaN` rather than some large number — and in PromQL, `NaN > 10` is `false`. A fully
  unreachable replica is the worst case this alert exists to catch, and it produced no
  alert. The fix adds an explicit `or redis_replica_lag_available == 0` branch that
  checks the paired availability gauge instead of trying to make the comparison itself
  handle the missing-data case. Any alert built on a probe-driven gauge should ask
  whether the probe's own failure mode already looks like "no problem" to the comparison
  operator.

`prometheus-rules.test.yaml` (run by `promtool test rules` in
[`.github/workflows/prometheus-rules.yml`](../../.github/workflows/prometheus-rules.yml))
gives every alert both a case that must fire and a **near-miss** that must not — the
near-miss matters more than it looks, because an expression that fires on everything
passes a fire-only suite while being useless. `RedisReplicaLagHigh` additionally gets a
second full test case exercising the `_available == 0` branch by itself, since the
first branch's near-miss (lag under threshold) does not exercise it.

## 5. What is deliberately absent

This is the section that prevents false confidence — read it before assuming any of the
following works.

- **No Grafana.** Nothing in this change renders, validates, or even references a
  dashboard. Dashboards drift from reality faster than any other observability artifact
  — a panel querying a renamed or removed metric fails silently, showing a flat line or
  "no data" that looks like normal quiet rather than broken tooling. Building one was out
  of scope; building one without a mechanism to keep it honest would be worse than not
  having one.
- **No log-derived metrics, no metric-derived logs.** Restated from §1 because it is the
  load-bearing design decision, not a gap: nothing here parses a log line into a counter,
  and nothing here formats a metric value into a log event. The two pipelines share no
  code path after the point where the request-handling code calls into each independently.
- **No tracing backend.** `traceId` exists in MDC and gets attached to log events, but
  only the Spring model service (8080) ever populates it — `TraceIdAspect` is Spring AOP,
  and the three Armeria mains (6010, 7010, 8010) run entirely outside a Spring container,
  so there is no proxy for the aspect to weave into. There is also no trace collector —
  no Jaeger, no Zipkin, no OpenTelemetry Collector — anywhere in this repo or its
  manifests. **Do not assume distributed tracing works.** A `traceId` search in Splunk
  will only ever surface model-service events; it cannot follow a request across the
  gateway → catalog/online/model hop it actually took.
- **No Prometheus.** Every manifest in this change — the four `ServiceMonitor`s, the one
  `PrometheusRule` — is a Kubernetes custom resource that a **Prometheus Operator**
  interprets. Nothing here installs one. This is not a new assumption introduced by this
  change: the two `ServiceMonitor`s that predate it (for Redis and for whatever this
  cluster already ran) made exactly the same assumption, silently, the whole time. If no
  Prometheus Operator is running in a given cluster, every `ServiceMonitor` and the
  `PrometheusRule` here are inert YAML — `kubectl apply` succeeds, the objects exist, and
  nothing evaluates them, scrapes anything, or fires an alert. **A committed alert file
  is evidence that alerts are written, not that anything is evaluating them** — confusing
  the two is precisely the failure this investigation exists to prevent, and it is the
  same shape of failure as §3.2's three-layer scrape gap: a manifest that looks like
  working observability and is not, until someone checks.

## 6. How this stays honest

Four separate mechanisms, each closing a gap the others cannot see:

- **`RecsysTargetDown`** (§4) catches a target that existed and went silent — but its
  blind spot is a target that never existed in the first place: `up{}` has no series for
  a `ServiceMonitor` that was never created, so there is nothing to be zero.
- **`ScrapeTargetManifestTest`** ([`src/test/java/com/recsys/metrics/ScrapeTargetManifestTest.java`](../../src/test/java/com/recsys/metrics/ScrapeTargetManifestTest.java))
  closes exactly that blind spot, statically, for the three silent layers in §3.2: it
  asserts every service in `EXPECTED_SCRAPE_TARGETS` has a `ServiceMonitor`, that every
  `ServiceMonitor`'s `selector.matchLabels` actually matches some Service's
  `metadata.labels` (not just any Service's pod selector), and that every scraped
  service's `NetworkPolicy` admits ingress from `app.kubernetes.io/name: prometheus`. It
  runs in the `-Presilience` PR gate — pure YAML parsing, no cluster, no Docker.
- **`promtool`** ([`.github/workflows/prometheus-rules.yml`](../../.github/workflows/prometheus-rules.yml))
  actually *executes* the eight alert expressions against synthetic time series
  (`k8s/base/prometheus-rules.test.yaml`), pinned to a specific Prometheus release rather
  than `latest`. This is the only mechanism in the repo that proves an alert expression
  behaves the way its prose claims — `ScrapeTargetManifestTest` proves the plumbing that
  would let Prometheus *see* the metric, not that the alert *fires* correctly on it. The
  two `for:`/window and `NaN`-comparison traps in §4 were both caught here, by writing the
  near-miss case first.
- **`DocumentationIndexTest`** ([`src/test/java/com/recsys/docs/DocumentationIndexTest.java`](../../src/test/java/com/recsys/docs/DocumentationIndexTest.java))
  keeps this document itself from going stale in the one way that is mechanically
  checkable: every file under `docs/system_design/` must have an entry point in
  `README.md`'s documentation map, and every link in that map must resolve to a real
  file. It cannot check that the *content* here still matches the code — only that the
  index is complete and the links are not dangling.

None of these four proves the whole chain end-to-end in one shot — a Prometheus Operator
that silently stopped evaluating rules, or a real cluster's `serviceMonitorSelector`
using a `release` label other than `kube-prometheus-stack`, would slip past all four.
That gap is inherent to testing manifests that assume infrastructure this repo does not
provision (§5) — closing it fully would mean standing up a real Prometheus Operator in
CI, which nothing here does today.
