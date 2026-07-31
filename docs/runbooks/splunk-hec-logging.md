# Splunk HEC logging

All four service mains — RecSys Serving API (6010), Model Serving (8080), Online Serving
(7010), API Gateway (8010) — can ship their structured JSON log events straight to
Splunk's HTTP Event Collector (HEC), in addition to the console/`logs/*.log` output they
already produce. It is off unless `SPLUNK_HEC_TOKEN` is set: with no token,
`SplunkHecAppender` starts, does nothing, and every request path is unaffected. Console
logging is never gated on this — the `CONSOLE` and `SPLUNK` appenders are attached
side by side in `src/main/resources/logback-common.xml`, included by the single
`src/main/resources/logback.xml` that all four mains load (see
["A Logback constraint"](#a-logback-constraint) below for why there is no separate
Spring config).

Design: `docs/superpowers/specs/2026-07-31-splunk-hec-log-shipping-design.md`

**Side effect: the three Armeria mains now run at INFO, not DEBUG.** Before this
feature, RecSys Serving (6010), Online Serving (7010), and API Gateway (8010) had no
Logback configuration at all and fell back to Logback's built-in `BasicConfigurator`,
which attaches a console appender to root at DEBUG. `logback-common.xml`'s root logger
is INFO, so this change also raises those three services' effective log level.
Almost certainly an improvement — none of this repo's own packages issue `log.debug`
calls on their hot paths, so nothing first-party is lost — but it is a real,
previously-undocumented operational change that ships alongside the Splunk feature.

## A Logback constraint

There is only one Logback config file in this repo, `src/main/resources/logback.xml`,
and it governs all four service mains — including the Spring Boot model service on
8080, not just the three Armeria mains that run outside Spring entirely.

That is not an oversight; it is forced by how Spring Boot resolves its logging config.
`AbstractLoggingSystem.getSelfInitializationConfig()` scans for **standard-location**
files first — `logback-test.xml`, then `logback.xml` — and returns as soon as it finds
one, *before* it ever looks for a `-spring` variant such as `logback-spring.xml`.
Because `logback.xml` exists on the classpath, Spring Boot finds it on that first pass
and never reaches the `-spring` lookup at all. A `logback-spring.xml` file would
therefore be unreachable dead code, which is why this repo has none — it was deleted
during implementation once this precedence was confirmed (see commit `793067f`).

The practical consequence: **Spring-only Logback tags — `<springProfile>`,
`<springProperty>` — cannot be used anywhere in this repo's Logback config.** They only
have any effect inside a `-spring` file, and no `-spring` file is ever loaded while
`logback.xml` is present. If you need Spring-profile-aware logging configuration, it has
to happen in a different phase (e.g. driven by a plain environment variable read inside
`SplunkHecConfig` or an equivalent class), not via a Logback tag. Someone will eventually
reach for `<springProfile>` inside `logback-common.xml` or `logback.xml`; it will parse,
Joran will silently ignore it outside a `-spring` file, and the intended behavior will
simply never happen. This section exists so that person finds the answer before losing
an afternoon to it.

## The delivery contract: at-most-once

`SplunkHecAppender` is shaped like `AsyncEventPublisher`: a bounded in-memory queue, one
daemon drain thread, batched HTTP POSTs, and **drop-on-full rather than block**. Logs are
diagnostics, not the system of record — a Splunk outage or a burst of log volume must
degrade to console-only, never stall a serving thread or grow the heap without bound.

Concretely, an event is dropped and never retried when:
- the bounded queue (`SPLUNK_HEC_QUEUE_CAPACITY`, default 10,000) is full, or
- the HTTP POST to Splunk fails for any reason (connection refused, timeout, TLS
  failure, a non-2xx response) — `SplunkHecClient.send` never throws and never retries.

**A Splunk search result is therefore a lower bound on what was logged, not a complete
record.** `stdout` / `logs/*.log` (the `CONSOLE` appender, which is never dropped or
gated) is the authoritative copy. State this plainly to anyone who wants to use a Splunk
`count` as evidence of anything — it can only ever undercount, never overcount.

## Local bring-up

**Status: this stack is not yet verified end to end.** It was built and desk-checked
(the compose file and `init.sh` parse cleanly, and every REST call in `init.sh` was
checked against Splunk's published API docs), but has never been run to a healthy
`splunk-init` on the host available during implementation, because:

**It requires an x86_64 host.** `splunk/splunk` publishes no arm64 image. On Apple
Silicon, Docker Desktop will run the image emulated (the compose file forces
`platform: linux/amd64`), but `splunkd` segfaults during first-boot indexing under
emulation:

```
Received fatal signal 11 (Segmentation fault) on PID <n>. Crashing thread: IndexerTPoolWorker-0
```

with a companion failure in `splunkd.log`:

```
Failed to start mongod on first attempt reason=KVStore service will not start because kvstore process terminated
```

This reproduced on two independent boots, including one against freshly emptied
volumes, so it is not stale state — it is `splunkd` faulting under x86_64-on-arm64
emulation, most plausibly in the KVStore/mongod component. Run this stack on a genuine
x86_64 host (a Linux box, an amd64 CI runner, or a Docker Desktop emulation setup that
doesn't hit this fault) to actually exercise it.

**What HAS been verified**, independent of a running Splunk instance: the appender's HEC
wire format, its batching behavior, and the `Authorization: Splunk <token>` header are
all proven against a stub HTTP collector in `SplunkHecAppenderTest`. All five
`Splunk*`/observability test classes (`SplunkHecConfigTest`, `SplunkHecEventSerializerTest`,
`SplunkHecClientTest`, `SplunkHecAppenderTest`, `SplunkLogbackWiringTest`) run in the
`-Presilience` PR gate, and the full suite passes (1488/1488 at the time this was
written). None of that exercises a real Splunk instance, index creation, or the HEC
provisioning REST calls in `init.sh` — that gap is exactly what running this stack on
x86_64 closes.

**Which HEC-enabling path actually works is also unconfirmed.** Two mechanisms are in
place and neither has been observed working end to end:
1. The Splunk image's own env vars (`SPLUNK_HEC_TOKEN`, `SPLUNK_HEC_SSL: "False"`)
   are honoured by its Ansible provisioning layer, in theory.
2. `docker/splunk/init.sh` independently repairs the same state via Splunk's
   management REST API (create the index, force HEC's global `enableSSL` off, create
   the token with a pinned value) and then polls the plain-HTTP collector endpoint
   until it accepts an event.

Whoever runs this first on x86_64 should confirm which path (or both) is doing the real
work — a related upstream issue (`splunk/docker-splunk#40`) documents `SPLUNK_HEC_TOKEN`
historically not being reliably honoured for *standalone* instances, which is why
`init.sh`'s management-API repair exists as a fallback rather than trusting the env var
alone — and update this section with the answer.

### Bring-up sequence

```bash
export SPLUNK_PASSWORD=changeme-splunk-admin
export SPLUNK_HEC_TOKEN=local-dev-hec-token
docker compose -f docker-compose.splunk.yml up -d
# First boot takes 1-3 minutes — splunk-init waits on the splunk service's healthcheck,
# then polls the plain-HTTP collector endpoint for up to 150s. Be patient.

sh scripts/run-microservices-local.sh   # picks up SPLUNK_HEC_TOKEN from the environment
open http://localhost:8000              # Splunk web UI: admin / $SPLUNK_PASSWORD
```

`scripts/run-microservices-local.sh` defaults `SPLUNK_HEC_URL` to
`http://localhost:8088/services/collector/event` rather than the `http://splunk:8088/...`
the compose network resolves — the four JVMs run on the host, not inside the compose
network, so only the published `localhost` port is reachable from them.

Once services are up, exercise at least one endpoint (e.g. `curl localhost:8010/health`
or a real recommendation request) and check the useful searches below.

### Recovering from an interrupted first boot

If the `splunk` container is killed (or the host restarts) before it finishes
initializing, `splunk-etc`/`splunk-var` are left half-provisioned, and the next start
fails fast with:

```
Checking appserver port [127.0.0.1:8065]: ...
ERROR: appserver port [127.0.0.1:8065] - port is already bound.
```

This looks alarming — it names a port — but nothing external is bound there; it is an
internal port check tripping over a second `splunkd` starting against stale state. The
fix is to discard the half-initialized volumes and start clean:

```bash
docker compose -f docker-compose.splunk.yml down -v
```

### Token rotation on surviving volumes

`init.sh` is idempotent for a **stable** token: re-running it against existing volumes
(e.g. a plain restart) is a no-op. It is not idempotent across a token *change*. Splunk
has no in-place update for an HEC token's value on a name collision, and `init.sh`
neither deletes nor recreates the token — it only attempts to create one named `recsys`.
If `SPLUNK_HEC_TOKEN` changes between runs against volumes that already have a `recsys`
token provisioned, the **old** token stays live and `splunk-init` exits 1 trying to
verify the new one. Recovery is the same as above: `down -v`, then bring the stack up
with the new token from empty volumes.

### Resourcing

Splunk wants more CPU and memory than Docker Desktop's 2 CPU / 4 GB default. This does
not cause the arm64 segfault above (that is an emulated-instruction fault, not a
resource limit), but it will slow first boot further on any host. Give the Splunk
container more room if you can.

## Enabling in EKS

**No Splunk is deployed by these manifests.** `k8s/base`, `k8s/eks-shared`, and both
region overlays (`k8s/eks`, `k8s/eks-us-west-2`) contain no Splunk Service, Deployment,
or `ExternalName` — `grep -rn splunk k8s/` turns up only the `SPLUNK_*` env blocks in
the four service Deployments. Every one of them hardcodes
`SPLUNK_HEC_URL: http://splunk:8088/services/collector/event`, a bare Kubernetes service
name that resolves to nothing in-cluster until something provides it. Creating the
Secret below and restarting is **not sufficient by itself** — without also repointing
`SPLUNK_HEC_URL`, all four services will have a live token and every batch will fail
DNS resolution against `splunk`. This fails safe (console logging is unaffected,
`TRANSPORT_FAILURE` is throttled), but it does not ship anything to Splunk.

The `recsys-splunk` Secret (key `hec-token`) is wired as `optional: true` into all four
`k8s/base/*.yaml` deployments, the same pattern as `recsys-online-admin` /
`SHARD_ADMIN_TOKEN` — pods are schedulable before the Secret exists, and the appender
stays inert. Creating the Secret does not affect already-running pods: the appender
reads its configuration from the environment once, at Logback startup, so an existing
pod keeps shipping nothing (or keeps using an old token) until it restarts.

```bash
kubectl -n recsys create secret generic recsys-splunk --from-literal=hec-token='<token>'
```

**Before restarting, repoint `SPLUNK_HEC_URL` at a real collector.** Depending on where
Splunk actually runs, that is one of:

- **In-cluster Splunk Service** — deploy (or point to) a Service literally named
  `splunk` in the same namespace, matching the manifests' default as-is, and skip the
  next step.
- **Externally hosted Splunk, reachable via a stable DNS name** — add a Kubernetes
  `ExternalName` Service named `splunk` that points at the real collector hostname, so
  the manifests' existing `http://splunk:8088/...` default resolves correctly with no
  patch to the Deployments themselves.
- **Any other real HEC endpoint** — patch each Deployment's `SPLUNK_HEC_URL` `value:`
  (in `k8s/base/api-gateway.yaml`, `catalog-serving.yaml`, `model-serving.yaml`,
  `online-serving.yaml`) to the actual collector URL via a Kustomize overlay patch, and
  re-render (`kubectl kustomize k8s/eks`) before applying.

Only once one of those is in place does a rollout restart actually start shipping logs:

```bash
kubectl -n recsys rollout restart deployment/recsys-api-gateway
kubectl -n recsys rollout restart deployment/recsys-catalog-serving
kubectl -n recsys rollout restart deployment/recsys-model-serving
kubectl -n recsys rollout restart deployment/recsys-online-serving
```

Confirm the Secret name (`recsys-splunk`) and key (`hec-token`) match what each
deployment's `SPLUNK_HEC_TOKEN` env entry references — see the `SPLUNK_*` block in
`k8s/base/api-gateway.yaml` (and the equivalent block in the other three manifests) for
the exact `secretKeyRef`.

`SPLUNK_SERVICE_NAME` and `SPLUNK_HEC_URL` are plain `value:` entries per manifest, not
sourced from the Secret — only the token is sensitive.

## Disabling

```bash
kubectl -n recsys delete secret recsys-splunk
kubectl -n recsys rollout restart deployment/recsys-api-gateway
kubectl -n recsys rollout restart deployment/recsys-catalog-serving
kubectl -n recsys rollout restart deployment/recsys-model-serving
kubectl -n recsys rollout restart deployment/recsys-online-serving
```

Because the Secret is `optional: true`, deleting it does not stop pods from being
schedulable; the appender simply comes back up inert on the next restart. Console
logging continues completely untouched throughout.

## Useful searches

```
index=recsys sourcetype="recsys:app:log" | stats count by source
```

`source` is `SPLUNK_SERVICE_NAME`, so this breaks counts down per service
(`recsys-serving`, `model-serving`, `online-serving`, `api-gateway` locally; see
`scripts/run-microservices-local.sh` and the `k8s/base/*.yaml` manifests for exactly
which value each service sets).

Filter to errors only:

```
index=recsys sourcetype="recsys:app:log" level=ERROR
```

Correlate one request via `traceId`:

```
index=recsys sourcetype="recsys:app:log" traceId="<id>"
```

**`traceId` is only populated by the model service (port 8080).** `TraceIdAspect` is
Spring AOP, wired through Spring's proxying — the three Armeria mains (RecSys Serving,
Online Serving, API Gateway) never populate it, because there is no Spring container
to weave the aspect into. Do not expect cross-service trace correlation through
`traceId` today; a search for one will only ever surface model-service events.

## Tuning

| Variable | Default | Effect |
|---|---|---|
| `SPLUNK_HEC_TOKEN` | unset | Enablement switch. Unset means the appender is a no-op — console-only. |
| `SPLUNK_HEC_URL` | `http://splunk:8088/services/collector/event` | Full collector URL. |
| `SPLUNK_HEC_INDEX` | `recsys` | Splunk index events are written to. |
| `SPLUNK_HEC_SOURCETYPE` | `recsys:app:log` | Splunk `sourcetype` field. |
| `SPLUNK_SERVICE_NAME` | `recsys` | Per-service `source` field — set uniquely by each of the four mains. |
| `SPLUNK_HEC_QUEUE_CAPACITY` | `10000` | Bounded queue depth before events are dropped. |
| `SPLUNK_HEC_BATCH_SIZE` | `100` | Max events per POST. |
| `SPLUNK_HEC_LINGER_MS` | `1000` | How long the drain thread waits for a batch's first event before checking again. |
| `SPLUNK_HEC_TIMEOUT_MS` | `2000` | Connect + request timeout. |
| `SPLUNK_HEC_INSECURE_TLS` | `false` | Accept Splunk's self-signed certificate when the URL is `https://`. Dev-only — see below. |

What to change, and when:
- **`dropped` climbing during bursts** (visible as repeated `TRANSPORT_FAILURE`/queue
  pressure in the Logback status output — see Diagnosing below) → raise
  `SPLUNK_HEC_QUEUE_CAPACITY`. The queue is sized for steady-state; a burst that
  outruns it is exactly the scenario drop-on-full exists to survive without blocking a
  serving thread, but a queue that is too small will drop routinely rather than only
  under genuine overload.
- **Want higher steady-state throughput** → raise `SPLUNK_HEC_BATCH_SIZE`, so more
  events go out per HTTP POST.
- **Collector is far away / slow to respond** → raise `SPLUNK_HEC_TIMEOUT_MS` so a
  distant collector's normal latency doesn't get misclassified as a transport failure.

`SPLUNK_HEC_INSECURE_TLS=true` disables Splunk TLS certificate verification entirely. It
exists only to point a developer at a local Splunk instance's stock self-signed
certificate over `https://`. It defaults to `false` and is never set in any committed
manifest — do not set it against anything but a local, disposable instance.

## Diagnosing

**The appender cannot log its own failures through slf4j.** Any slf4j call from inside
`SplunkHecAppender` would route back into the very appender attached to the root
logger and recurse. Instead, it reports through Logback's own status API
(`addInfo`/`addWarn`/`addError`), which surfaces as plain lines on stdout, formatted
like:

```
WARN in ch.qos.logback.classic.LoggerContext - Splunk HEC delivery failed (SERVER_ERROR); dropped 12 events. Total failed: 12
```

**These never reach Splunk** — that's the whole point of routing them elsewhere,
since the appender cannot depend on the thing it's trying to diagnose. If you are
debugging "why is nothing arriving in Splunk," look at the service's own stdout /
`logs/<service>.log`, not at a Splunk search.

**Never set `-Djdk.httpclient.HttpClient.log` (or the `jdk.httpclient.HttpClient.log`
system property another way) on a service with this appender attached.** It is the
obvious first move when debugging "nothing is arriving in Splunk," and it is the wrong
one. The JDK's `HttpClient` logs through `System.Logger`, and this repo's classpath
routes `System.Logger` → `java.util.logging` → `SLF4JBridgeHandler` → slf4j → Logback →
this very appender (`jul-to-slf4j` arrives via `spring-boot-starter-logging`, and Spring
Boot installs the bridge handler). The only thing standing between that chain and a
recursive loop is that `HttpClient`'s own request/response logging defaults to off.
Turning it on makes every HTTP POST this appender's client makes emit a log line, which
gets enqueued, which gets shipped, which logs again — a self-sustaining amplification
loop. It will not stack-overflow (Logback's per-thread re-entrancy guard in
`UnsynchronizedAppenderBase` prevents that), so instead the drain thread pins itself in
the loop indefinitely, which is arguably worse: quieter, and it stops shipping every
other log line while it happens.

`SplunkHecClient.send` classifies every outcome into one of four values
(`SplunkHecClient.Outcome`), and a warning is throttled to once per outcome kind per
minute (an unthrottled warning per failed batch would itself become a log flood during
an outage):

| Outcome | Meaning |
|---|---|
| `SUCCESS` | Splunk accepted the batch (2xx). |
| `AUTH_REJECTED` | Splunk returned 401/403 — the token is wrong, expired, or was never actually created for this index. Logged as `addError`, with an explicit "Check SPLUNK_HEC_TOKEN." suffix. |
| `SERVER_ERROR` | Splunk returned some other non-2xx status. |
| `TRANSPORT_FAILURE` | Connection refused, DNS failure, timeout, TLS failure, or any other exception from the HTTP call — including a batch that failed to serialize, and including a caught `Throwable` from the drain loop itself (an `OutOfMemoryError` or `StackOverflowError` most likely to fire during the very burst this appender exists to survive). |

Startup itself logs one line either way, also through the status API: either
`Splunk HEC appender shipping to <url> (index=..., source=...)` when enabled, or
`Splunk HEC appender disabled: <reason>` when not (info-level for the ordinary "no
token" case, error-level if a token was supplied but the rest of the configuration —
e.g. `SPLUNK_HEC_URL` — is unusable).

## Divergences from a production Splunk

Mirroring `docs/runbooks/cdn-local.md`'s framing: this is a demonstration of HEC
ingestion, not an emulation of a production Splunk deployment.

- **Single instance, no clustering.** No indexer cluster, no search head cluster, no
  cluster master. A production deployment would spread ingestion and search load
  across multiple peers; this is one container.
- **No forwarders.** Events go straight from the JVMs to HEC. There is no universal or
  heavy forwarder tier, no intermediate buffering beyond this appender's own bounded
  queue.
- **No retention tiering.** No hot/warm/cold/frozen bucket lifecycle, no archiving, no
  data retention policy beyond whatever this Splunk instance's defaults are.
- **HEC on plain HTTP with a shared dev token.** `init.sh` deliberately forces the
  global HEC setting to `enableSSL=0` so the collector accepts plain-HTTP POSTs (see
  the compose file's `SPLUNK_HEC_SSL: "False"` and `init.sh`'s explicit `enableSSL=0`
  call) — this exists purely so a local run needs no certificate trust setup. A real
  Splunk HEC endpoint should be TLS, and a real deployment issues distinct tokens per
  source rather than one shared `local-dev-hec-token` across every developer's stack.

This is a wiring harness for the appender's HEC contract, not a production Splunk
stand-in.
