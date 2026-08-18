# Latency and memory observability — design

Make request latency and JVM memory answerable in both halves of the observability split: as
searchable per-event records in Splunk, and as scrapeable series with alerts in Prometheus.
Neither derived from the other.

## What is actually missing

The request was "monitor latency and memory in Splunk". Measuring the current state first moved
most of the work somewhere else.

**Splunk contains no latency or memory data of any kind.** The HEC appender ships all four
services' Logback events and has since #259, but nothing on any request path logs a duration.
There is no Armeria `AccessLogWriter` anywhere, and MDC is populated only by `TraceIdAspect`,
which is Spring AOP and therefore covers the model service alone. The runbook's search inventory
is level, `source`, `traceId` and `exception` — nothing else exists to search.

**JVM memory is not in Prometheus for three of the four services.** This is the finding that
reshapes the work. All three Armeria mains build their registry through
`PrometheusMeterRegistries.defaultRegistry()`, and Armeria's `configureRegistry` is a literal
no-op — decompiled, it null-checks its argument and returns it. No `JvmMemoryMetrics`,
`JvmGcMetrics` or `JvmThreadMetrics` binder is called anywhere in `src/main`. Heap usage, GC
pause time and thread count have never been scrapeable on 6010, 7010 or 8010. The model service
gets them from Actuator's auto-configuration and is the only one that does.

`JvmMemoryMonitor` and `GcEventTracker` exist and are good — a poll-based four-region snapshot
and a JMX notification listener with per-pause timing — but both are Spring `@Service` beans read
only by `HealthController`. They are pull-only endpoints on 8080. Nothing ships them anywhere and
nothing outside Spring can construct them.

**Latency in Prometheus is one service wide, and the inventory understates it.** Online serving
mounts `MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("online_serving"))`,
so `online_serving_request_duration_seconds` is a real histogram — but §8.3's table lists only the
hand-rolled `online_serving_p50_ms`/`_p95_ms`/`_p99_ms` gauges and omits it. Neither
`RecSysServer` nor `MicroserviceGatewayServer` mounts that decorator, so 6010 and the gateway have
no request-duration series at all. No latency alert exists in `prometheus-rules.yaml`, and no
heap or GC alert either.

## Why this does not collapse the Splunk/Prometheus boundary

§8 of [18_Fault_Tolerance](../../system_design/18_Fault_Tolerance.md) states a load-bearing rule:
no metric is computed by parsing a log, and no log line exists to represent a metric. §8.5 lists
it as a design decision rather than a gap. Putting latency and memory into both tools has to
answer to that.

It does, because the two halves carry different information and neither is computed from the
other:

- **Splunk gets edge-triggered events.** A request that exceeded a threshold. A GC pause that
  exceeded a threshold. A heap crossing. Each is a discrete thing that happened at a time, with
  high-cardinality context attached — route, status, `traceId`, GC cause. This is exactly the
  "what happened in this request" half.
- **Prometheus gets continuous series.** Histograms and gauges sampled every 15 s, low
  cardinality, suitable for `rate()` and alert expressions. This is the "is the system healthy"
  half.

**Edge-triggered is the property that keeps the boundary.** A periodic heap sample shipped to
Splunk would be a metric wearing a log's clothes, and was explicitly rejected. Nothing in this
design emits on a timer, and no Prometheus series is derived from any log field: every new metric
is read off a Micrometer binder or an Armeria decorator, both of which observe the same runtime
state the log emitter observes, independently.

§8 gains a paragraph saying this, so the next reader does not have to re-derive it.

## Part A — Splunk: two event types

### A1. Slow and failed request events

A decorator on the three Armeria mains and a `HandlerInterceptor` on the model service. On
response completion, emit **one WARN, only when** the request was slow or unsuccessful:

- `durationMs > SLOW_REQUEST_LOG_THRESHOLD_MS` (default 500, `EnvConfig.readLong`), or
- the response is 5xx.

`RequestOutcome.classify` is the single definition both emitters share, and it recognizes exactly
two outcomes: `"slow"` and `"failed"`. There is no third trigger for admission-control rejection,
load shedding, or circuit-breaker refusal as such — those are visible to this event only insofar
as they happen to produce a 5xx (`"failed"`) or exceed the duration threshold (`"slow"`).
`classify(429, 1, 500)` returns `null`: a rejection that surfaces as 429 emits no Splunk event at
all, pinned by `RequestOutcomeTest`.

**4xx does not qualify, full stop — including rejections.** A malformed cursor or a missing API key
is a client error, not a service incident, and 4xx is the one class an external caller can generate
at will. Making *any* 4xx a log trigger, rejections included, hands anyone with a URL the ability to
fill the bounded HEC queue on demand: hammer a rate limiter and you get one 429 per request, which
is exactly the bounded-queue-fill vector 4xx was excluded for in the first place. This was
considered and rejected rather than merely never built:

1. **Rejection *rates* are already fully covered by Prometheus** —
   `online_serving_rejected_rate`, `recsys_load_shedder_requests_total{result}`, and
   `recsys_online_rate_limit_decisions_total{source,result}` all exist today. A Splunk event per
   rejection would be a metric wearing a log's clothes, the exact §8 boundary violation this design
   exists to avoid.
2. **A 429 is caller-forceable.** Any external caller can generate one on demand by hammering the
   rate limiter, so logging on 429 reopens the same queue-fill vector excluded above — the
   protection is not that 4xx as a class is safe to ignore, it's that nothing about a 4xx trigger
   can be made safe against a caller who wants to trigger it.

**Known limitation, stated rather than papered over:** a *shed* request can surface as either status,
and the two are not treated alike. `RecSysServer` sheds with 503, and `classify` labels any 5xx
`"failed"` — so a deliberate, working-as-designed shed is indistinguishable in Splunk from a genuine
failure. A shed that surfaces as 429 produces no event at all, per the 4xx rule above. Neither case
gets its own `"rejected"`/`"shed"` outcome value; an operator who needs to tell them apart reaches for
the Prometheus series named in point 1, not Splunk.

A fast, successful request logs nothing. This is deliberate and is the whole volume story: the
appender is at-most-once over a bounded drop-on-full queue, so logging every request would push
the queue into a régime where it discards indiscriminately — taking the ERROR events the existing
runbook searches depend on with it, and firing `SplunkHecDroppingEvents` as a side effect of a
feature that is supposed to improve observability. Splunk's job here is "show me the slow ones",
not "compute a distribution"; the distribution is Part B's.

Fields ride MDC, which `SplunkHecEventSerializer` already promotes into the `event` object with no
serializer change:

| Field | Notes |
|---|---|
| `service` | which of the four |
| `route` | route pattern, never the raw path |
| `method` | HTTP method |
| `status` | response status code |
| `outcome` | `slow`, `failed` — see A1's rejection/shedding discussion above for why there is no third value |
| `durationMs` | measured wall time |
| `traceId` | model service only today — see the tracing caveat below |

None of these collide with the serializer's `RESERVED_KEYS`, which drops MDC entries named
`level`, `logger`, `thread`, `message`, `exception`, `host`, `source`, `sourcetype`, `index` or
`time`. That must be asserted, not assumed: a future field named `time` would be silently dropped.

MDC must be cleared on the emitting thread after the event. Armeria hands requests to pooled event
loop threads, so a leaked MDC entry attaches itself to unrelated later log lines.

### A2. GC pause and heap-pressure events

`GcEventTracker` already receives a JMX `GarbageCollectionNotificationInfo` callback per collection
carrying the pause duration and before/after usage per pool. It gains an emitter:

- **GC pause event** — a stop-the-world pause exceeding `GC_PAUSE_LOG_THRESHOLD_MS` (default 200)
  logs one WARN with `gcName`, `gcAction`, `gcCause`, `pauseMs`, `heapBeforeBytes`,
  `heapAfterBytes`, `heapUsedFraction`.
- **Heap-pressure crossing** — when post-collection heap used fraction crosses
  `HEAP_PRESSURE_THRESHOLD` (default 0.90) **upward**, one WARN. When it falls back below
  `HEAP_PRESSURE_RECOVERY_THRESHOLD` (default 0.80), one INFO. Edge-triggered with hysteresis, so
  a heap oscillating around the line produces two events rather than one per collection.

The concurrent collectors are excluded from the pause event: a ZGC cycle's reported wall time
includes concurrent phases and its true STW pause is sub-millisecond, so a wall-time threshold
would fire constantly on a healthy ZGC service. `GcEventTracker` already classifies collectors into
`YOUNG`/`FULL`/`CONCURRENT` roles; the emitter reuses that classification rather than adding a
second one.

### A3. Making the JVM monitors usable outside Spring

`GcEventTracker` registers its JMX listeners under `@PostConstruct` and is a `@Service`, so only
the model service can construct it. Registration moves into an explicit `start()`; Spring's
`@PostConstruct` calls it, and the three Armeria mains call it directly during boot. `destroy()`
already exists via `DisposableBean` and gains a plain counterpart the Armeria mains can call on
shutdown.

This is the minimum change that makes the class reusable. The `@Service` annotation stays so the
model service's wiring is untouched.

## Part B — Prometheus: close the measured gap

### B1. Bind the JVM metrics

A shared `JvmMetricsBinder.bindTo(MeterRegistry)` calling `JvmMemoryMetrics`, `JvmGcMetrics`,
`JvmThreadMetrics` and `ProcessorMetrics`, invoked by all three Armeria mains. One helper rather
than three call sites so the set cannot drift between services and can be asserted once.

`JvmGcMetrics` is `Closeable` and holds a JMX listener; the mains must retain and close it, which
also means it is a strong reference and not exposed to the `WeakReference` trap §8.3 documents for
`Gauge.builder`. That claim gets verified against a real registry rather than assumed — the
Splunk metrics bug was exactly this shape.

The model service already has all four from Actuator and is not touched.

### B2. Request-duration histograms for the remaining two services

`MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault(prefix))` added to
`RecSysServer` and `MicroserviceGatewayServer`, matching online serving's existing pattern, with
prefixes `catalog_serving` and `api_gateway`.

**The gateway's cardinality must be checked before this ships.** Its data path is a catch-all
`sb.service("prefix:/", ...)`. If `MeterIdPrefixFunction.ofDefault` tags by route pattern the label
set is bounded and this is safe; if it tags by request path, every distinct URL becomes a label
value and this is a cardinality bomb on the one service that sees arbitrary paths. Verify by
reading the actual `/metrics` exposition after a handful of varied requests, not from
documentation.

### B3. Alerts

Three new rules in `k8s/base/prometheus-rules.yaml`, each with a `promtool` fire case **and** a
near-miss, per the rule §8.6 exists to enforce.

| Alert | Expression shape | Threshold | `for:` |
|---|---|---:|---:|
| `JvmHeapPressureHigh` | heap `used / max` over the heap area | `> 0.90` | 10 m |
| `JvmGcTimeFractionHigh` | `rate(jvm_gc_pause_seconds_sum[5m])` — seconds of STW pause per second of wall time | `> 0.10` | 10 m |
| `RequestLatencyP99High` | `histogram_quantile(0.99, sum by (le, job) (rate(<prefix>_request_duration_seconds_bucket[5m])))` | see below | 10 m |

These are starting values, chosen to be defensible rather than measured: 0.90 heap matches the
Splunk-side `HEAP_PRESSURE_THRESHOLD` so the two tools agree on what "under pressure" means, and
10% of wall time in stop-the-world pause is a well-worn rule of thumb for a JVM in GC trouble.
Each is a `PrometheusRule` edit rather than a code change and should be retuned once real
baselines exist; the promtool cases pin the *behaviour* of each expression, not the number.

**A latency threshold above the enforced request timeout can never fire, and one of the three
services enforces one.** `OnlinePredictionServer` sets `requestTimeoutMillis` from
`ONLINE_REQUEST_TIMEOUT_MS`, default **500 ms**, so `online_serving_request_duration_seconds` is
bounded near 0.5 by construction and any threshold at or above that produces an alert that looks
like coverage and cannot fire — the exact failure §8.4's header warns about. Thresholds are
therefore derived from each service's own ceiling rather than shared:

| Service | Enforced timeout | p99 threshold |
|---|---|---:|
| Online serving (7010) | 500 ms (`ONLINE_REQUEST_TIMEOUT_MS`) | `> 0.4` |
| Catalog serving (6010) | none set — Armeria default | `> 1` |
| API gateway (8010) | none set — Armeria default | `> 2` |

The gateway is loosest because its p99 contains a backend's p99 by construction. The 6010 and 8010
values inherit Armeria's default request timeout, which must be confirmed at implementation time
rather than assumed; if either service later gains an explicit timeout, its threshold has to move
with it. That coupling is a standing hazard and belongs in §8.4's trap list, because nothing in
`promtool` can detect it: an unreachable threshold passes a near-miss case perfectly.

For the same reason `SLOW_REQUEST_LOG_THRESHOLD_MS` defaults per service rather than globally —
500 ms on online serving would emit only for requests that already timed out, which is a
strictly smaller set than "slow".

**`JvmHeapPressureHigh` is a NaN trap in waiting, and §8.4 documents this failure twice already.**
`jvm_memory_max_bytes` reports `-1` for pools with no maximum. A naive
`jvm_memory_used_bytes / jvm_memory_max_bytes > 0.9` divides by a negative for those pools, and an
absent series makes the whole expression NaN — and `NaN > 0.9` is false, so the alert stays silent
in exactly the case it exists for. The expression pins to the heap area and specific pools, and
the promtool suite includes a case where the max is unset that must still behave correctly.

`for:` stays well below each `rate()`/`increase()` range window, per the first trap in §8.4.

## What this deliberately does not do

- **No periodic memory sampling into Splunk.** Considered and rejected; it is the one shape that
  would genuinely violate the §8 boundary.
- **No metric derived from any log field**, and no log emitted to feed a metric.
- **No Grafana, no dashboards.** §8.5's "nothing renders or validates a dashboard" is unchanged.
- **No tracing backend, and `traceId` coverage does not improve.** §8.5 is explicit that only the
  model service populates `traceId` and no collector exists. The slow-request event carries the
  field where it is available, which means a slow gateway request and the slow backend request it
  caused **cannot be correlated by `traceId`** — only by timestamp and route. Recording this
  rather than implying otherwise, because a `traceId` field present on some events and absent on
  others invites exactly the wrong inference.
- **No change to the appender, the serializer, or the delivery contract.** Delivery stays
  at-most-once; a Splunk search for slow requests is a lower bound, and under load it is a lower
  bound precisely when load is the thing being investigated.

## Testing

Unit, non-docker, and **added to the `-Presilience` profile** — that profile is an allow-list, so a
test outside it does not gate a merge:

- The request decorator emits at and above the threshold, and stays silent below it; a failed fast
  request still emits; MDC is cleared afterward on the emitting thread.
- The MDC field names do not intersect `SplunkHecEventSerializer.RESERVED_KEYS`, asserted against
  that set directly so adding a colliding field later fails the build.
- `GcEventTracker`'s emitter is edge-triggered: crossing up logs once, staying above logs nothing
  further, recovery logs once. Concurrent collectors do not produce pause events.
- `JvmMetricsBinder` registers the expected meter names on a real `PrometheusMeterRegistry`, and
  the meters still report after a forced GC — the same proof shape as
  `SplunkHecMetricsTest#metersSurviveGarbageCollectionOfTheirBackingState`.

`promtool test rules` in `.github/workflows/prometheus-rules.yml` gains a fire case and a near-miss
per new alert, plus the unset-max case for `JvmHeapPressureHigh`.

**Every assertion must be shown to fail before it is trusted.** Raise the threshold and the
emit test must go red; rename a bound meter and the binder test must name it. A conformance test
written against code that already passes proves the test compiles, nothing more.

**The Splunk end-to-end path is verifiable only in CI.** `docker-compose.splunk.yml` cannot run on
this arm64 host — Splunk publishes no arm64 image and `splunkd` segfaults during first-boot
indexing under emulation. Verification that the new events reach Splunk and are searchable rides
`SplunkHecIntegrationTest` (`@Tag("docker")`) on the x86_64 runner. Unit tests cover the emit
decision; only CI covers the wire.

## Documentation

No new numbered document. The content lands where its subject already lives:

- **`18_Fault_Tolerance.md` §8.3** — new metrics added to the inventory, and the existing omission
  of `online_serving_request_duration_seconds` corrected.
- **§8.4** — the three new alerts in the table, with the NaN hazard noted alongside the two
  sentinel traps already recorded there.
- **§8** — a paragraph stating why latency and memory appearing in both tools is not a boundary
  violation, so the rule is not read as having quietly lapsed.
- **§8.5** — the `traceId` bullet extended to say the slow-request event inherits that limit.
- **`docs/runbooks/splunk-hec-logging.md`** — searches under "Useful searches": slow requests by
  route and service, GC pauses by service, and correlating a slow request with a GC pause by
  timestamp, with the at-most-once caveat restated at the point of use.
- **`.claude/CLAUDE.md`** — the four new environment variables.
