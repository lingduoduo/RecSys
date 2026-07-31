# Splunk HEC log shipping — design

**Date:** 2026-07-31
**Status:** approved, not yet implemented

Ship this repo's application logs as structured events directly to Splunk's HTTP Event
Collector, so all four services' logs are searchable in one place instead of living in
four `logs/*.log` files (locally) or four sets of pod stdout (in EKS).

## Scope

**In scope:** a Logback appender that POSTs structured JSON log events to
`http://splunk:8088/services/collector/event`; wiring for all four service mains; a
local Splunk stack that provisions itself; tests; a runbook.

**Out of scope, deliberately:**

- Routing *domain* events (`LogCollector.UserBehaviorLog`, A/B exposures, saga
  transitions) to Splunk. Those already have a transport abstraction
  (`AsyncEventPublisher` → log-only/Kafka/SQS) and a separate delivery contract; adding a
  HEC transport there is a distinct feature.
- Metrics. Prometheus already covers that surface (`/metrics` on the gateway,
  Micrometer throughout).
- Splunk dashboards, saved searches, or alerts.
- A Universal Forwarder sidecar. The requirement is explicitly *direct* HEC delivery.

## Architecture

One new package, `com.recsys.infrastructure.observability` — a technical adapter, which
is what `infrastructure/` holds per the package map. Four classes, each with one job and
testable on its own:

| Class | Responsibility | Depends on |
|---|---|---|
| `SplunkHecConfig` | Read and validate `SPLUNK_*` environment; expose `isEnabled()` | nothing |
| `SplunkHecEventSerializer` | Map one `ILoggingEvent` to one HEC event JSON object | Jackson |
| `SplunkHecClient` | POST a batch body to the collector endpoint | `java.net.http.HttpClient` |
| `SplunkHecAppender` | Logback appender: bounded queue, drain thread, batching | the three above |

Data flow:

```
log.info(...)
  → Logback
    → SplunkHecAppender.append()      request thread ends here (nanoseconds)
      → ArrayBlockingQueue.offer()    drop, never block, when full
        ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  thread boundary
        → drain thread: batch by size or linger time
          → SplunkHecClient.send()
            → POST http://splunk:8088/services/collector/event
```

This is deliberately the same shape as
[`AsyncEventPublisher`](../../../src/main/java/com/recsys/infrastructure/messaging/AsyncEventPublisher.java):
a bounded queue, a single daemon drain thread, batched writes, and **drop-on-full rather
than block**. Logs are diagnostics. A Splunk outage or a log burst must degrade to
console-only output; it must never stall a serving thread or grow the heap without bound.

### The recursion constraint

The appender must never log through slf4j — doing so routes back into itself and
recurses until the stack or the queue blows up. This applies to every path in all four
classes, including error handling. All internal diagnostics use Logback's own status API
(`addWarn`, `addError`, `addInfo`), which writes to Logback's status manager and cannot
re-enter the appender.

The JDK's `HttpClient` logs through `System.Logger`, not slf4j, and its request logging
is off unless `jdk.httpclient.HttpClient.log` is set — so it does not create a cycle.
This is a property of the chosen client, and is the reason not to swap in an slf4j-backed
HTTP client later without re-checking it.

The `CONSOLE` appender stays attached to root unconditionally. Nothing that reaches
Logback is ever lost from stdout, whatever Splunk is doing.

## Event shape

One HEC event object per log event, in Splunk's documented envelope:

```json
{
  "time": 1753970000.123,
  "host": "recsys-gateway-7c9f-x2k",
  "source": "api-gateway",
  "sourcetype": "recsys:app:log",
  "index": "recsys",
  "event": {
    "level": "WARN",
    "logger": "com.recsys.application.gateway.LlmProxy",
    "thread": "armeria-common-worker-1",
    "message": "upstream timed out after 2000ms",
    "traceId": "a1b2c3d4",
    "exception": "java.io.IOException: ...\n\tat ..."
  }
}
```

Rules:

- `time` is epoch **seconds with millisecond fraction**, which is the format HEC parses.
- `host` is the JVM's hostname (`InetAddress.getLocalHost().getHostName()`, resolved once
  at start; falls back to `unknown` if resolution throws — it does on some Docker
  configurations, and that must not prevent the appender from starting).
- `source` is `SPLUNK_SERVICE_NAME`, so the four mains are distinguishable even though
  they ship from one image.
- `exception` is present only when the event carries a throwable, and holds the rendered
  stack trace including causes.
- MDC entries merge into `event`. **Reserved keys win**: an MDC entry named `level`,
  `logger`, `thread`, `message`, or `exception` is dropped rather than shadowing the real
  field, so a search on `level=ERROR` cannot be poisoned by application code.
- `traceId` comes from the existing MDC key set by
  [`TraceIdAspect`](../../../src/main/java/com/recsys/tracing/TraceIdAspect.java). That is
  a Spring AOP aspect, so today only the model service (8080) populates it; on the three
  Armeria mains the field is simply absent. Propagating trace IDs into Armeria is
  separate work and not part of this change.

A batch is the newline-concatenation of these objects in a single POST body — what the
`/services/collector/event` endpoint expects. Requests carry
`Authorization: Splunk <token>` and `Content-Type: application/json`.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `SPLUNK_HEC_TOKEN` | unset | **Unset means the appender is a no-op.** Console-only. |
| `SPLUNK_HEC_URL` | `http://splunk:8088/services/collector/event` | Full collector URL |
| `SPLUNK_HEC_INDEX` | `recsys` | |
| `SPLUNK_HEC_SOURCETYPE` | `recsys:app:log` | |
| `SPLUNK_SERVICE_NAME` | `recsys` | Per-service `source` field |
| `SPLUNK_HEC_QUEUE_CAPACITY` | `10000` | Bounded queue depth |
| `SPLUNK_HEC_BATCH_SIZE` | `100` | Max events per POST |
| `SPLUNK_HEC_LINGER_MS` | `1000` | How long the drain thread waits for a batch's first event |
| `SPLUNK_HEC_TIMEOUT_MS` | `2000` | Connect + request timeout |
| `SPLUNK_HEC_INSECURE_TLS` | `false` | Accept a self-signed cert when the URL is `https://` |

`SPLUNK_HEC_TOKEN` being the enablement switch means local dev, tests, and CI are
unaffected with no extra flag: no token, no Splunk traffic, no connection-refused noise.

### Two hostnames, one default

The default URL uses the Docker/Kubernetes service name `splunk`, which is correct when
the JVM shares a network with Splunk — an EKS pod, or a container on the compose network.
But `scripts/run-microservices-local.sh` starts the four mains **on the host**, where
`splunk` does not resolve and only the published `localhost:8088` does. Leaving the
default alone would mean the primary local flow silently fails DNS on every batch.

So the default stays `http://splunk:8088/services/collector/event`, and the local script
overrides it: `SPLUNK_HEC_URL="${SPLUNK_HEC_URL:-http://localhost:8088/services/collector/event}"`.
Each context gets a working value with no per-developer setup, and an explicit export
still wins in both.

`SPLUNK_HEC_INSECURE_TLS` exists because a stock Splunk deployment serves HEC over HTTPS
with a self-signed certificate. The local stack disables HEC TLS entirely (below) so
plain HTTP works as specified; the flag is for pointing a developer at a real Splunk
instance without minting a trusted cert.

`SPLUNK_HEC_LINGER_MS` bounds only the wait for a batch's *first* event; once one is
available the batch ships immediately, topped up with whatever else is already queued. So
batches fill naturally under load and a lone event is never held back — the same drain
shape as `AsyncEventPublisher.drainLoop`.

Malformed numeric values fall back to the default rather than failing startup, matching
`AsyncEventPublisher.readIntEnv`. A blank or malformed `SPLUNK_HEC_URL` with a token
present *does* disable the appender, with an `addError` explaining why — silently
shipping nowhere would be worse than a loud no-op.

## Logback wiring

Today `logback-spring.xml` defines a console appender and is picked up by Spring Boot
only. The three Armeria mains have no Logback configuration at all and fall back to
Logback's built-in default. Sharing one appender definition across all four requires
factoring it out:

- **new** `src/main/resources/logback-common.xml` — defines `CONSOLE` and `SPLUNK`
  appenders and the root logger.
- **new** `src/main/resources/logback.xml` — includes the common file. This is the single
  entry point for all four mains. When Logback self-initializes, it checks standard
  locations (`logback-test.xml`, `logback.xml`) *before* Spring Boot's `-spring` locations.
  So `logback.xml` wins for all four services, including Spring Boot.
- `src/test/resources/logback-test.xml` — **unchanged**.

The old `logback-spring.xml` is deleted: it cannot be reached while `logback.xml` exists on
the classpath, so it would be dead code.

What the test suite actually loads is worth stating precisely, because it determines
whether a test-only opt-out is needed:

- All tests prefer `logback-test.xml` over `logback.xml`, so they never construct the
  Splunk appender. This applies to Spring Boot tests and plain-Logback tests uniformly,
  because Logback's precedence is checked *before* Spring Boot's.

No opt-out flag is needed anyway, but the reason is the token check, not file precedence:
with `SPLUNK_HEC_TOKEN` unset in CI the appender starts disabled and issues no request.
`SplunkLogbackWiringTest` asserts exactly that, so the property is enforced rather than
assumed.

Spring-only Logback tags (`springProfile`, `springProperty`) cannot be used in
`logback-common.xml` because they only work from a `-spring` file, and `logback-spring.xml`
can never be reached while `logback.xml` exists on the classpath. All service identity here
comes from `SPLUNK_SERVICE_NAME`, set uniformly by all four mains.

No conditional-processing (`<if>`) blocks, and therefore no Janino dependency: the
enablement check lives inside `SplunkHecAppender.start()`. When disabled, `start()`
records an `addInfo` and `append()` returns immediately.

### Per-service environment

- `scripts/run-microservices-local.sh` passes `SPLUNK_SERVICE_NAME` per service
  (`recsys-serving`, `model-serving`, `online-serving`, `api-gateway`), defaults
  `SPLUNK_HEC_URL` to the `localhost` spelling described above, and forwards
  `SPLUNK_HEC_TOKEN` and the remaining `SPLUNK_HEC_*` tunables from the environment.
- `k8s/base/*.yaml` sources `SPLUNK_HEC_TOKEN` from a `recsys-splunk` Secret with
  `optional: true`, matching the `recsys-online-admin` / `SHARD_ADMIN_TOKEN` pattern —
  pods stay schedulable before the Secret exists, and Splunk shipping switches on when it
  is created. `SPLUNK_SERVICE_NAME` and `SPLUNK_HEC_URL` are plain `value:` entries.

## Local stack

`docker-splunk.yml` becomes `docker-compose.splunk.yml`, matching the existing
`docker-compose.cdn.yml` / `docker-compose.streaming.yml` naming. Two fixes to the file
as drafted:

1. The `volumes:` block carries four stale `redis-*` entries copied from another compose
   file, none of which any service in it references. Removed.
2. Starting Splunk is not enough — HEC is off by default, has no token, and the `recsys`
   index does not exist.

So a one-shot `splunk-init` service is added. It waits on the `splunk` service's existing
healthcheck (`depends_on: condition: service_healthy`) and then, via the management API on
port 8089:

- creates the `recsys` index if absent,
- enables the HEC global endpoint with `enableSSL=0`, so plain
  `http://splunk:8088` works as specified,
- creates an HEC token whose value is pinned to `SPLUNK_HEC_TOKEN` from the environment,
  so the app and Splunk agree on it without a copy-paste step.

It is idempotent — re-running `up` against existing volumes is a no-op — so the
volume-backed Splunk instance survives restarts without drift.

Result: `docker compose -f docker-compose.splunk.yml up`, then
`sh scripts/run-microservices-local.sh` with `SPLUNK_HEC_TOKEN` exported, gives
searchable logs at `localhost:8000` with no manual UI steps.

## Error handling

| Failure | Behaviour |
|---|---|
| Splunk down, 5xx, or timeout | Batch dropped; throttled `addWarn`; `failed` counter incremented. **No retry.** |
| Queue full during a log burst | Event dropped; `dropped` counter incremented. Console still has it. |
| 403 (bad or rotated token) | `addError` once, then throttled; draining continues, since the token may be rotated in place. |
| Serialization throws on one event | That event is skipped; the rest of the batch still ships. |
| Hostname resolution fails at start | `host` becomes `unknown`; the appender still starts. |
| JVM shutdown | `stop()` interrupts the drain thread and flushes the remainder with a bounded 2s wait, mirroring `AsyncEventPublisher.close()`. |

**No retry is a deliberate choice, not an omission.** Retrying inside the drain thread
converts a Splunk outage into a growing backlog and delays every subsequent batch;
at-most-once matches the contract the rest of this repo's asynchronous paths already
document, and the console appender is the durable copy. If delivery guarantees are ever
needed for logs, the answer is a forwarder with an on-disk queue, not in-process retry.

Throttling means: log the first occurrence, then at most one message per 60s per failure
kind. An unthrottled warning per failed batch would itself become a log flood during an
outage.

A `Snapshot(queued, sent, dropped, failed)` record is exposed for tests and for future
ops surfacing. Micrometer is not wired: the appender is constructed by Logback before any
Spring context exists, and there is no consumer for the numbers today.

## Testing

Three test classes, all non-docker, all added to the `-Presilience` Surefire profile so
they actually gate a PR (the CI gate runs only that profile):

**`SplunkHecConfigTest`** — defaults; env override of each field; malformed integers fall
back rather than throw; disabled when the token is absent; disabled with `addError` when
the URL is blank or malformed.

**`SplunkHecEventSerializerTest`** — field mapping for a plain event; `time` formatted as
epoch seconds with millisecond fraction; MDC entries merged; reserved-key collision
dropped rather than shadowing; throwable rendered with causes; null message tolerated;
batch body is newline-concatenated objects.

**`SplunkHecAppenderTest`** — against a real stub collector built on `armeria-junit5`
(already a dependency, so no new test infrastructure):

- events arrive at `/services/collector/event`, batched, with
  `Authorization: Splunk <token>`
- a partial batch ships after the linger interval
- queue-full drops rather than blocks, and increments `dropped`
- disabled config produces zero requests
- **a stub returning 503, or refusing connections, never throws into the caller of
  `append()`** — the property the whole design exists to preserve
- `stop()` flushes buffered events before returning

## Documentation

- **new** `docs/runbooks/splunk-hec-logging.md` — local bring-up, enabling it in EKS
  (creating the `recsys-splunk` Secret), useful searches, tuning the queue/batch knobs,
  and what the at-most-once contract means when reading a Splunk result set.
- README documentation map gains the runbook entry. `DocumentationIndexTest` asserts this
  in both directions and fails the build otherwise.
- `.claude/CLAUDE.md` env-var section gains the `SPLUNK_*` variables.

Not woven into a numbered system-design investigation: none of the twenty covers
logging or observability, and forcing it into the closest neighbour would misfile it.
If an observability investigation is ever written, this is its material.
