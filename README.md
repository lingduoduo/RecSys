# RecSys Backend Service

This repository is a Java 17/Maven recommendation-system backend with four
independently runnable HTTP services. It includes catalog and recommendation
serving, online features, ONNX model serving, an API gateway, and the Redis,
Kafka, and Flink infrastructure used by the local demo.

This README is the contributor entry point: build the project, start it
locally, check health, run the right tests, and find the document that owns a
deeper topic. Architecture and production operations stay in the linked
system-design documents and runbooks.

## What runs locally

The standard local workflow starts two groups of processes:

1. `docker-compose.streaming.yml` starts the supporting infrastructure:
   ZooKeeper, Kafka, a Redis primary and replica, three Redis Sentinel
   processes, and a Flink JobManager and TaskManager.
2. `scripts/run-microservices-local.sh` starts four Java services from this
   Maven project:
   catalog/recommendation serving, online prediction, model serving, and the
   API gateway.

The Java services run on the host so that contributors can rebuild, attach a
debugger, or restart one process without rebuilding containers. The supporting
infrastructure stays in Docker.

MySQL-backed catalog routes are optional and disabled by default. The ordinary
quick start needs no MySQL server, database migration, cloud credentials, or
external LLM service. It does make an explicit local-only authentication choice
for the gateway, described below.

The gateway starts after the three backends and aggregates their current
health, so its endpoint is the single full-stack check used below.

## Prerequisites

Required:

- JDK 17. The Maven enforcer rejects other Java major versions.
- Maven available as `mvn`; this repository has no Maven wrapper.
- Docker Engine with the Docker Compose v2 plugin (`docker compose`).
- A POSIX-compatible shell for the repository scripts.
- `curl` for the health checks below.

On macOS, Colima is one option for providing the Docker daemon:

```bash
colima start
```

Colima is not required on Linux, and Docker Desktop is also a valid local
Docker provider. Start whichever Docker provider you use before running
Compose.

Run all commands from the repository root.

## Five-minute quick start

Start the Docker-backed infrastructure:

```bash
docker compose -f docker-compose.streaming.yml up -d
```

Build the application without running tests:

```bash
mvn package -DskipTests
```

Start all four Java services:

```bash
GATEWAY_ALLOW_ANONYMOUS=true sh scripts/run-microservices-local.sh
```

> **Development only:** this setting deliberately disables gateway
> authentication and treats every caller as anonymous. Never use it in
> production. Configure API-key or Cognito authentication using the
> [Configuration Guide](CONFIG_GUIDE.md).

Keep that terminal open. The script owns the four child processes and writes
their output to:

```text
logs/recsys-serving.log
logs/model-serving.log
logs/online-serving.log
logs/api-gateway.log
```

The script waits ten seconds before starting the gateway. In another terminal,
check the aggregate health endpoint:

```bash
curl --fail http://localhost:8010/health
```

`curl --fail` exits nonzero while an upstream is unavailable, so retry after
checking the service logs if the initial build or model startup is still in
progress.

To stop the Java services, press `Ctrl-C` in the terminal running
`run-microservices-local.sh`. Its shutdown trap terminates all four child
processes.

Stop the supporting containers while retaining the Redis volumes:

```bash
docker compose -f docker-compose.streaming.yml down
```

> **Destructive local reset:** the following command stops the stack and
> deletes its named Redis and Sentinel volumes. Use it only when you intend to
> discard local state.

```bash
docker compose -f docker-compose.streaming.yml down --volumes
```

## Services and health checks

| Service | Main class / start command | Health endpoint | Purpose |
|---|---|---|---|
| Catalog and recommendation serving (`6010`) | `com.recsys.api.serving.RecSysServer` via `mvn exec:java` | `http://localhost:6010/health` | Catalog reads, embedding recall, multi-channel recommendation, and prediction-compatible routes |
| Online prediction (`7010`) | `com.recsys.api.online.OnlinePredictionServer` via `mvn exec:java` | `http://localhost:7010/health` | Redis-backed online features, recent behavior, trending recall, and online recommendation |
| Model serving (`8080`) | `com.recsys.api.rest.ModelApplication` via `mvn spring-boot:run` | `http://localhost:8080/health/ready` | Spring Boot ONNX inference, ranking, version management, caching, and load shedding |
| API gateway (`8010`) | `com.recsys.api.gateway.MicroserviceGatewayServer` via `mvn exec:java` | `http://localhost:8010/health` | Routing, upstream health aggregation, authentication, rate limiting, and circuit breaking |

Use liveness to answer “is the process alive?” and readiness to answer “should
this process receive new work?” The online and model services expose
`/health/live` and `/health/ready`; the catalog also exposes `/health/ready`.

The stable status semantics are:

| Endpoint | Status | Stable reason | Contributor action |
|---|---:|---|---|
| Catalog `/health` | `200` | The service handler is available. | Use `/health/ready` when checking admission readiness. |
| Catalog `/health/ready` | `503` | The catalog load shedder is draining. | Wait for in-flight work to fall or inspect the catalog log. |
| Online `/health/live` | `200` | The online-serving process is alive. | Use readiness before sending recommendation traffic. |
| Online `/health` or `/health/ready` | `503` | The instance is draining because of utilization or shutdown. | Stop new requests and inspect `online-serving.log`. |
| Model `/health/live` | `200` | The Spring Boot process is alive. | Use readiness before sending inference traffic. |
| Model `/health/ready` | `503` | `model not loaded`, `shutting down`, `overloaded`, `high failure rate`, or `high inference latency`. | Use the returned reason and `model-serving.log`; do not rely on sampled counters. |
| Gateway `/health` | `503` | At least one configured upstream health check is down, so aggregate status is degraded. | Probe ports `6010`, `7010`, and `8080`, then inspect the matching log. |

Health bodies contain live counters, latency, timestamps, and route details.
Those values are intentionally not copied into this README because they vary
on every run. The gateway health contract is described in the
[API gateway investigation](docs/system_design/09_API_Gateway.md).

## Common contributor workflows

### Start one service

Run one command per terminal. Start Redis first for the catalog and online
services. Start all three backends before the gateway if you want the gateway
aggregate health check to return success.

Catalog and recommendation serving:

```bash
env PORT=6010 sh scripts/run-with-jvm-tuning.sh recsys-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.api.serving.RecSysServer
```

Online prediction:

```bash
env ONLINE_DEMO_PORT=7010 \
  sh scripts/run-with-jvm-tuning.sh online-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.api.online.OnlinePredictionServer
```

Model serving:

```bash
env SERVER_PORT=8080 sh scripts/run-with-jvm-tuning.sh model-serving -- \
  mvn spring-boot:run
```

API gateway:

```bash
env GATEWAY_PORT=8010 GATEWAY_ALLOW_ANONYMOUS=true \
  sh scripts/run-with-jvm-tuning.sh api-gateway -- \
  mvn exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer
```

The anonymous setting in the gateway command is development-only. The wrapper
loads the repository's checked-in JVM options. Use
`mvn package -DskipTests` for a fast rebuild and the all-service script from the
quick start when you do not need process-level isolation.

### Inspect processes and logs

Show container state and recent infrastructure logs:

```bash
docker compose -f docker-compose.streaming.yml ps
docker compose -f docker-compose.streaming.yml logs --tail=100 \
  redis-primary kafka jobmanager
```

Follow all Java service logs:

```bash
tail -f logs/recsys-serving.log \
  logs/model-serving.log \
  logs/online-serving.log \
  logs/api-gateway.log
```

### Load sample online features

With the Redis primary available on `localhost:6379` and `redis-cli`
installed:

```bash
sh streaming/online-serving/scripts/load_online_features.sh
```

The loader replays the checked-in
`src/main/java/com/recsys/data/online_features.txt` data into Redis. The full
streaming and Flink workflow lives in the
[online-serving guide](streaming/online-serving/README.md).

## Testing

The Maven default excludes JUnit tests tagged `load` or `docker`. This keeps
ordinary local and pull-request validation deterministic and independent of a
container daemon.

Validate the Java version and dependency convergence:

```bash
mvn --batch-mode validate
```

Run the focused deterministic resilience profile used by the pull-request
workflow:

```bash
mvn --batch-mode -Presilience test
```

Run the complete ordinary test suite:

```bash
mvn --batch-mode test
```

The resilience profile is still a deterministic unit/contract suite. It does
not opt into the load or Docker tags.

### Known clean-checkout artifact limitation

The pre-existing baseline does not track these fixtures:

```text
src/main/resources/artifacts/model/training/feature_config.json
src/main/resources/artifacts/model/test/feature_config.json
src/main/resources/artifacts/pyspark/als_model_metadata.json
```

Consequently, ordinary `mvn --batch-mode test` has fixture-related errors on a
clean checkout, and model serving on port `8080` may fail to become ready. This
README change does not claim that suite passes.

Restore the project-specific training/test fixtures from the modeling pipeline
under those classpath paths before running fixture-dependent tests. This
checkout has no artifact-preparation script; the `offline-embedding` Maven
profile only generates Word2Vec item embeddings and does not replace these
fixtures.

For model serving with already-prepared external artifacts, set
`RECSYS_MODEL_ARTIFACTS_DIR` to a root containing `training/` and `test/`
variant directories. Set `RECSYS_SPARK_ARTIFACTS_DIR` to the root containing
`als_model_metadata.json` when Spark artifacts are needed. Both names are bound
in [application.yml](src/main/resources/application.yml).

### Opt-in load suite

The `load` group contains bounded characterization and load-evidence tests.
It is not an ordinary pre-commit suite:

```bash
mvn --batch-mode test -DexcludedGroups=docker -Dgroups=load
```

Expect this group to consume more CPU and time than the default suite. Keep
Docker-tagged tests excluded so the result represents the load boundary only.

### Opt-in Docker suite

The `docker` group contains Testcontainers and infrastructure integration
tests. It requires a working Docker daemon:

```bash
mvn --batch-mode test -DexcludedGroups=load -Dgroups=docker
```

Keep load-tagged tests excluded so the result represents the Docker integration
boundary only. Some tests carry both tags and are therefore reserved for an
explicit combined environment, not either isolated command above.

Scheduled load and Docker jobs add evidence-output settings and upload the
resulting reports. See the
[fault-tolerance evidence section](docs/system_design/18_Fault_Tolerance.md#7-proving-the-failure-paths),
the [pull-request resilience workflow](.github/workflows/resilience-pr.yml),
and the [scheduled resilience workflow](.github/workflows/resilience-scheduled.yml)
for the maintained commands and artifact paths.

## Repository layout

```text
.
├── src/main/java/com/recsys/
│   ├── api/                  HTTP entry points and service main classes
│   ├── application/          recommendation, gateway, model, and workflow logic
│   ├── domain/               domain records and value objects
│   ├── infrastructure/       Redis, MySQL, messaging, caches, and integrations
│   ├── online/flink/         opt-in Flink online-feature job
│   ├── health/, loadshed/    health, admission, drain, and shutdown controls
│   └── config/, metrics/     runtime parsing and serving metrics
├── src/main/resources/       Spring config, fallback ONNX, and DB migrations
├── src/test/java/com/recsys/ matching unit, contract, load, and Docker tests
├── streaming/online-serving/ local event data, replay scripts, and guide
├── scripts/                  local startup and operational helpers
├── config/jvm/               per-service local JVM option profiles
├── docker/, k8s/             local infrastructure and deployment manifests
├── docs/                     subsystem investigations and runbooks
├── .github/workflows/        deterministic and scheduled resilience CI
├── docker-compose.streaming.yml
├── CONFIG_GUIDE.md
└── pom.xml
```

## Configuration

The quick start explicitly sets `GATEWAY_ALLOW_ANONYMOUS=true` for local
development. Its script supplies the standard service ports and gateway
upstream URLs, and Redis defaults to `localhost:6379`.

The local settings most often overridden are:

| Setting | Default | Local use |
|---|---:|---|
| `PORT` | `6010` | Catalog/recommendation service port |
| `ONLINE_DEMO_PORT` | `7010` | Online prediction service port |
| `SERVER_PORT` | `8080` | Spring Boot model-serving port |
| `GATEWAY_PORT` | `8010` | API gateway port |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Host Redis connection |
| `GATEWAY_ALLOW_ANONYMOUS` | `false` | Explicit development-only opt-in used by the quick start |

Do not copy service, resilience, authentication, or deployment variables into
new README tables. The authoritative defaults, parsing behavior, Kubernetes
overrides, and secret-handling guidance are in the
[Configuration Guide](CONFIG_GUIDE.md).

Production gateway deployments must use the documented API-key or Cognito
settings and leave anonymous mode disabled.

The shared container image selects a service with `RECSYS_MAIN_CLASS`;
Kubernetes manifests set that value per workload. Local Maven commands invoke
their main class directly.

MySQL remains off in the standard workflow. If you enable it, follow the
requirements for `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`, and
`MYSQL_CURSOR_SIGNING_KEY` in the Configuration Guide before starting the
catalog service.

## Troubleshooting

### Docker or infrastructure is unavailable

Start your Docker provider, then inspect the daemon and containers:

```bash
docker version
docker compose -f docker-compose.streaming.yml ps
docker compose -f docker-compose.streaming.yml logs --tail=200 \
  zookeeper kafka redis-primary jobmanager
```

On macOS with Colima, use `colima status` and `colima start`.
If intentionally discarding corrupted or incompatible local Redis state, use
the destructive volume-reset command from the quick start.

### A Java service exits during startup

Read the service-specific log:

```bash
tail -n 200 logs/recsys-serving.log
tail -n 200 logs/model-serving.log
tail -n 200 logs/online-serving.log
tail -n 200 logs/api-gateway.log
```

Common causes are a missing Redis connection, a port already in use, an
unsupported Java version, or invalid opt-in configuration. The catalog
service's MySQL configuration is validated only when MySQL is enabled.

### The gateway health check returns `503`

Probe the backend health endpoints directly:

```bash
curl --fail http://localhost:6010/health
curl --fail http://localhost:7010/health/ready
curl --fail http://localhost:8080/health/ready
```

The gateway intentionally returns `503` when any configured upstream route is
down. Start the missing backend or follow the stable readiness reason in its
own log and health response.

### A port is already in use

Identify the listening process, stop the previous local run, or use the
documented port override consistently:

```bash
lsof -nP -iTCP:6010 -sTCP:LISTEN
```

Changing a backend port also requires changing the corresponding gateway
upstream URL. See the Configuration Guide rather than changing only one side.

## Documentation map

Configuration and local operation:

- [Configuration Guide](CONFIG_GUIDE.md) — authoritative environment
  variables, defaults, parsing rules, and deployment overrides.
- [Online-serving and streaming guide](streaming/online-serving/README.md) —
  deeper material for sample events, Redis features, and the opt-in Flink
  workflow; this existing path is linked because there is no `docs/ml/` index.

Architecture:

- [System-design investigations](docs/system_design/) — the existing numbered
  investigation directory; there is no separate
  `docs/system_design/README.md` index.
- [API gateway](docs/system_design/09_API_Gateway.md) — route ownership,
  authentication, health aggregation, circuit breakers, and metrics.
- [Microservices](docs/system_design/10_MicroServices.md) — service boundaries,
  entry points, deployment model, and communication.
- [Fault tolerance](docs/system_design/18_Fault_Tolerance.md) — resilience
  contracts, graceful drain, failure-path evidence, and status.

Operational runbooks:

- [Overload protection](docs/runbooks/overload-protection.md) — overload
  symptoms, controls, validation, and recovery.
- [Regional failover](docs/runbooks/dr-regional-failover.md) — promote the
  standby region and capture evidence.
- [Regional failback](docs/runbooks/dr-failback.md) — return traffic to the
  recovered primary region.
- [DR game day](docs/runbooks/dr-game-day.md) — rehearse and evaluate a
  regional recovery.

The documentation map is intentionally curated. Browse `docs/system_design/`
for caching, sharding, replication, messaging, indexing, consistency, CDN, and
scalability investigations rather than duplicating their content here.

## Before opening a pull request

From a clean working tree, run at least:

```bash
mvn --batch-mode validate
mvn --batch-mode test
git diff --check
```

If the change touches circuit breakers, bulkheads, load shedding, rate
limiting, graceful shutdown, degraded recall, outbox, or saga behavior, also
run:

```bash
mvn --batch-mode -Presilience test
```

Run the opt-in `load` or `docker` group only when the change requires that
environment; report those results separately from the ordinary deterministic
suite.

Before requesting review:

- verify new or changed commands from the repository root;
- add or update tests in the matching package;
- keep configuration changes synchronized with `CONFIG_GUIDE.md`;
- update the owning system-design page or runbook for behavioral changes;
- avoid committing generated logs, Maven `target/` output, credentials, or
  local service data;
- confirm linked files and anchors exist;
- summarize which deterministic and opt-in suites you ran.
