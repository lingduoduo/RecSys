# Observability Phase 2: finish the Prometheus path — design

**Date:** 2026-07-31
**Status:** approved, not yet implemented

Consolidate this repo's observability story into two phases, and close the gaps in the
second one.

- **Phase 1 — application logs → Splunk HEC.** Shipped (#259, #262). Not changed here;
  this design describes it so the two halves sit in one document.
- **Phase 2 — system health → Prometheus.** Mostly built already. This design finishes
  it: the metrics nothing collects, the counters nothing exposes, and the alerts that do
  not exist.

## The honest starting position

Phase 2 is not "add Prometheus". Verified against `main`:

| Service | Instrumented | Exposes | **Scraped** |
|---|---|---|---|
| model-serving (8080) | yes | `/actuator/prometheus` | **yes** |
| catalog-serving (6010) | yes | `/metrics` | **yes** |
| online-serving (7010) | yes | `/metrics` | **no** |
| api-gateway (8010) | yes | `/metrics` | **no** |

`k8s/base/servicemonitor.yaml` defines exactly two `ServiceMonitor`s. So online-serving
and the gateway publish Prometheus exposition that nothing collects. The gateway is the
front door — circuit breakers, rate limiting, origin-secret rejections, service-registry
resolution — and none of it reaches a time series.

Also missing: any `PrometheusRule`, any runbook, and any Micrometer registration for the
Splunk appender's counters.

This is the same failure shape as two other things found this week: a scheduled workflow
that failed at "Set up job" for five days, and a load test whose fixtures were gitignored.
In all three, instrumentation *looked* present. **Absence and success are indistinguishable
unless something asserts the difference** — which is why the highest-value item in this
design is an alert that fires when a target stops being scraped.

## Scope

**In scope:** two missing `ServiceMonitor`s; a Micrometer bridge for the Splunk appender's
`Snapshot`; a `PrometheusRule` with a small alert set; `promtool` unit tests for those
alerts in CI; a consolidated `docs/system_design/21_Observability.md`.

**Out of scope, deliberately:**

- **Grafana dashboards.** No Grafana here to render or validate them; committed dashboard
  JSON would be an unverifiable artifact, and dashboards drift faster than anything else in
  observability.
- **Deploying Prometheus.** The manifests assume a Prometheus Operator already in the
  cluster, which is what the existing `ServiceMonitor`s assume too. This design does not
  change that.
- **Log-derived metrics or metric-derived logs.** See the boundary below.
- **New instrumentation for its own sake.** Every alert below uses a metric that already
  exists; the only new metrics are the appender bridge, which exposes counters Phase 1
  already computes.

## The boundary between the two phases

The two systems answer different questions, and keeping that line sharp is the reason
this is two phases rather than one pile:

- **Splunk answers "what happened in this request?"** — a specific `traceId`, an exception
  and its stack, the message a service logged at 03:14. High cardinality, per-event,
  at-most-once.
- **Prometheus answers "is the system healthy?"** — rates, saturation, latency
  distributions, over time. Low cardinality, aggregated, scraped.

Neither is derived from the other. No metric is computed by parsing logs, and no log line
exists solely to feed a metric. When both could answer a question, alerting reads
Prometheus and investigation reads Splunk.

**Delivery contracts differ, and that matters when reading them together.** Splunk is
at-most-once — a search result is a lower bound on what was logged. Prometheus is a
sampled gauge of a live process — a counter that never got scraped is simply absent. So
"nothing in Splunk" and "no data in Prometheus" both have two possible causes: it did not
happen, or the pipe is broken. Item (c) below exists to tell those apart for metrics; for
logs, stdout remains the authoritative copy.

## (a) Close the scrape gap

Add two `ServiceMonitor`s to `k8s/base/servicemonitor.yaml`, matching the shape of the two
already there (`port: http`, `interval: 15s`, `scrapeTimeout: 10s`):

| New monitor | Selector | Path |
|---|---|---|
| `recsys-online-serving` | `app: recsys-online-serving` | `/metrics` |
| `recsys-api-gateway` | `app: recsys-api-gateway` | `/metrics` |

Both services already serve Prometheus exposition on their main HTTP port via
`PrometheusExpositionService` (`OnlinePredictionServer.java:225`,
`MicroserviceGatewayServer.java:129`), so no application change is needed — only
collection.

**Verify the label selectors against the actual Service manifests before writing them.**
A `ServiceMonitor` whose selector matches nothing fails exactly as silently as having no
`ServiceMonitor` at all, which is the bug being fixed. The `RecsysTargetDown` alert below
is the backstop that makes a wrong selector loud rather than invisible.

## (b) Bridge Phase 1's counters into Phase 2

`SplunkHecAppender` maintains `sent`, `dropped`, `failed`, `indeterminate` and queue depth,
exposed only as a `Snapshot` record. Nothing outside its package reads it. So log-shipping
loss is currently visible only as `WARN in ch.qos.logback...` lines on stdout — which is
precisely where an operator will not be looking when they ask "are we losing logs?"

Register those as Micrometer meters:

| Meter | Type | Source |
|---|---|---|
| `splunk_hec_events_sent_total` | counter-style gauge | `Snapshot.sent()` |
| `splunk_hec_events_dropped_total` | counter-style gauge | `Snapshot.dropped()` |
| `splunk_hec_events_failed_total` | counter-style gauge | `Snapshot.failed()` |
| `splunk_hec_events_indeterminate_total` | counter-style gauge | `Snapshot.indeterminate()` |
| `splunk_hec_queue_depth` | gauge | `Snapshot.queued()` |

Registered as gauges reading a monotonically-increasing source, following the pattern the
repo already uses in `LoadShedder` (`Gauge.builder("recsys.load_shedder.utilization", this,
s -> s.snapshot().utilization())`) and `InferenceMetricsService`. Prometheus'
`increase()`/`rate()` work correctly over a monotonic gauge, and this avoids
double-counting that would come from incrementing a `Counter` alongside the existing
`AtomicLong`s.

**The ordering problem, and how it is solved.** The appender is constructed by Logback
before any `MeterRegistry` exists — that is why Phase 1 deliberately did not wire
Micrometer. The bridge therefore runs *after* registry construction and pulls the appender
off the root logger by name:

```java
LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
Appender<ILoggingEvent> appender = ctx.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("SPLUNK");
if (appender instanceof SplunkHecAppender splunk) { /* register gauges over splunk::snapshot */ }
```

A new `SplunkHecMetrics` class in `com.recsys.metrics` owns this, matching
`GatewayRegistryMetrics.register(...)`. It is a no-op when the appender is absent or
disabled, so nothing breaks when `SPLUNK_HEC_TOKEN` is unset — which is every local run and
every test.

Call sites differ by service, and the Spring one needs a new class:

- **The three Armeria mains** call it where they already build their registry —
  `MicroserviceGatewayServer.java:128` is the model (`PrometheusMeterRegistries.defaultRegistry()`
  immediately followed by `GatewayRegistryMetrics.register(...)`).
- **The Spring model service has no metrics configuration class today** — it relies on
  Actuator's auto-configured `MeterRegistry`. So this needs a small new
  `@Configuration` in `com.recsys.config` that injects `MeterRegistry` and calls
  `SplunkHecMetrics.register(registry)`. Do not bolt it onto
  `ModelRecommendationPipelineConfig`, which is about the inference pipeline and has no
  business owning appender wiring.

`getAppender("SPLUNK")` depends on the appender's name in `logback-common.xml`. That
coupling is real but narrow; the bridge's test asserts the lookup succeeds against the
actual config file, so a rename fails a test rather than silently producing no metrics.

## (c) Alerts

One `PrometheusRule` at `k8s/base/prometheus-rules.yaml`. Every expression below uses a
metric name extracted from the source, not invented — all seven were verified present
before this design was written.

**It must also be added to `k8s/base/kustomization.yaml`'s `resources:` list.** That list is
explicit — `servicemonitor.yaml` is on it — so a manifest file that exists but is not listed
renders as nothing at all, with no error. That is the same silent-absence failure this
design is otherwise about, one directory over. `kubectl kustomize k8s/base | grep
PrometheusRule` is the check, and it belongs in the plan's verification steps rather than
in someone's memory.

| Alert | Expression sketch | For | Severity | Meaning |
|---|---|---|---|---|
| `RecsysTargetDown` | `up{job=~"recsys-.*"} == 0` | 5m | critical | A service stopped being scraped. **The meta-alert.** |
| `SplunkHecDroppingEvents` | `increase(splunk_hec_events_dropped_total[10m]) > 0` | 10m | warning | Queue full; logs being lost |
| `SplunkHecIndeterminateDelivery` | `increase(splunk_hec_events_indeterminate_total[10m]) > 0` | 10m | warning | Batches sent, never acknowledged — possible loss *or* duplication |
| `GatewayRegistryStale` | `gateway_registry_snapshot_age_seconds > 120` | 5m | warning | Gateway resolving from a stale registry snapshot |
| `OnlineServingShedding` | `online_serving_rejected_rate > 0.1` | 10m | warning | Sustained load shedding |
| `RedisCacheUnavailable` | `redis_cache_available == 0` | 5m | critical | Cache probe cannot reach Redis |
| `RedisReplicaLagHigh` | `redis_replica_lag_seconds > 10` | 10m | warning | Replica reads returning stale data |
| `OutboxBacklogGrowing` | `outbox_pending_events > 1000 and increase(outbox_pending_events[15m]) > 0` | 15m | warning | Relay not keeping up; eventual consistency degrading |

Thresholds are starting points, and the doc says so. They are deliberately loose: an alert
that cries wolf gets muted, and a muted alert is worse than none. Tightening belongs with
production data, not with a design.

**`RecsysTargetDown` is the one that matters most.** It is the alert that would have caught
the gap this design exists to fix, and structurally the same class of bug as the dead
scheduled workflow and the gitignored test fixtures. `up` is synthesised by Prometheus for
every scrape target, so it needs no application support and cannot itself be forgotten in
the way an application metric can.

Its one blind spot: `up` only exists for targets Prometheus *knows about*. A
`ServiceMonitor` that was never created, or whose selector matches nothing, produces no
target and therefore no `up` series to be zero. That is why (a) is verified against real
Service labels rather than assumed. A follow-up worth considering — but out of scope here —
is an absence check on expected job names.

## (d) Prove the alerts fire

`promtool test rules`, run in CI. Each alert gets at least two cases: a series that must
fire it, and a near-miss that must not.

This is the load-bearing part of the design. Two artifacts shipped this week could not be
verified where they were written — the Splunk compose stack (arm64) and, before it was
fixed, the alerting story here. The lesson taken from that is not "test more", it is
**never ship an assertion nobody has watched succeed and fail**. `promtool` makes alert
expressions executable, so there is no excuse for the untested variant.

Test fixtures live at `k8s/base/prometheus-rules.test.yaml`, using promtool's own format:

```yaml
tests:
  - interval: 1m
    input_series:
      - series: 'up{job="recsys-api-gateway"}'
        values: '1+0x5 0+0x10'
    alert_rule_test:
      - eval_time: 10m
        alertname: RecsysTargetDown
        exp_alerts:
          - exp_labels: {severity: critical, job: recsys-api-gateway}
```

A new workflow, `.github/workflows/prometheus-rules.yml`, installs `promtool` (from the
`prom/prometheus` image or the released tarball) and runs `promtool check rules` plus
`promtool test rules`. Path-filtered to `k8s/base/prometheus-rules*.yaml` and the workflow
itself, plus `workflow_dispatch` — the same shape as `splunk-hec-integration.yml`, and for
the same reason: fast feedback where it matters, no cost on unrelated PRs.

**Pin the promtool version explicitly.** An unpinned `latest` makes the one check that
validates the alerts itself unreproducible, and pinned-but-wrong is how the `setup-java`
SHA broke the weekly workflow for five days — so the pin is resolved from a real release,
not copied.

## (e) The consolidated document

`docs/system_design/21_Observability.md` — a new number, not a renumbering of any existing
investigation, which the repo's conventions forbid. There is no existing home: none of the
twenty investigations covers logging or metrics, and the Splunk design already anticipated
this ("if an observability investigation is ever written, this is its material").

Contents:

1. **The two questions** — the Splunk/Prometheus boundary above, stated first because it is
   what makes the rest coherent.
2. **Phase 1: logs** — what ships to Splunk, the at-most-once contract, and the pointer to
   `docs/runbooks/splunk-hec-logging.md` rather than a duplicate of it.
3. **Phase 2: metrics** — what each of the four services exposes and where; the metric
   inventory grouped by subsystem (serving, gateway, Redis, outbox, inference, load
   shedding, Splunk shipping); the two naming conventions in play and why they differ.
4. **Alerts** — each one's meaning, likely cause, and first response. An alert without a
   documented response is a pager that teaches people to ignore it.
5. **What is deliberately absent** — no Grafana, no log-derived metrics, no tracing backend
   (`traceId` exists in MDC but there is no collector; naming it here stops the next person
   assuming distributed tracing works).
6. **How this stays honest** — `RecsysTargetDown`, the promtool tests, and the
   `DocumentationIndexTest` requirement.

The metric inventory is the section most likely to rot. It will name where each metric is
registered in source, so a reader can check rather than trust.

README's documentation map gains the entry — `DocumentationIndexTest` asserts both
directions and runs in the PR gate, so a missing entry fails the build. `.claude/CLAUDE.md`
gains a short Prometheus paragraph beside the existing `SPLUNK_*` one.

## Testing

| What | How |
|---|---|
| Alert expressions | `promtool check rules` + `promtool test rules` in CI, fire and near-miss cases per alert |
| Manifests | `kubectl kustomize` over `k8s/base`, `k8s/eks`, `k8s/eks-us-west-2`, **and grep the output for `PrometheusRule` and for four `ServiceMonitor`s** — building cleanly proves nothing about whether a new file was actually included |
| ServiceMonitor selectors | Assert each selector matches the labels on the corresponding Service manifest, so a typo fails rather than silently collecting nothing |
| Appender bridge | Unit test: registers against a real `PrometheusMeterRegistry`, reports the snapshot's values, is a no-op when the appender is absent or disabled, and finds the `SPLUNK` appender in the real `logback-common.xml` |
| Docs | `DocumentationIndexTest` |

The bridge test joins the `-Presilience` profile — it is pure unit-level, and the PR gate is
the only place a check can block a merge.

## Risks

**A `ServiceMonitor` selector that matches nothing** looks identical to success. Mitigated
by the selector test and by `RecsysTargetDown`.

**Alert fatigue from loose thresholds.** Accepted deliberately: the alternative is
tight thresholds guessed without production data, which fire spuriously and get muted. The
document states the thresholds are provisional.

**The appender-name coupling** in `getAppender("SPLUNK")` breaks silently if the appender is
renamed in `logback-common.xml`. Mitigated by a test that performs the lookup against the
real config file.

**Prometheus is assumed, not deployed.** If no Prometheus Operator is running, the
`ServiceMonitor`s and `PrometheusRule` are inert CRs. That is already true of the two
existing monitors; this design does not make it worse, but the document says so plainly so
nobody reads a committed alert file as evidence that alerting exists.
