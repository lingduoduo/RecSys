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

The clean-clone quick start uses the smallest useful runnable subset:

1. `docker-compose.streaming.yml` starts the Redis primary.
2. The catalog/recommendation service runs on the host and exposes health on
   port `6010`.

The full local topology additionally has ZooKeeper, Kafka, a Redis replica,
three Redis Sentinel processes, Flink, online prediction, ONNX model serving,
and the API gateway. The Java services run on the host so contributors can
rebuild, attach a debugger, or restart one process without rebuilding
containers.

MySQL-backed catalog routes are optional and disabled by default. The ordinary
quick start needs no MySQL server, database migration, cloud credentials, or
external LLM service. It also avoids the untracked model and Spark artifacts
that a clean clone cannot generate. The artifact-dependent four-service
workflow and its explicit gateway authentication choice are documented under
[Common contributor workflows](#common-contributor-workflows).

## Prerequisites

Required:

- JDK 17. The Maven enforcer rejects other Java major versions.
- Maven available as `mvn`; this repository has no Maven wrapper.
- Docker Engine with the Docker Compose v2 plugin (`docker compose`).
- A POSIX-compatible shell for the repository scripts.
- `curl` for the health checks below.

On macOS, Colima (`colima start`) or Docker Desktop can provide the daemon.
Colima is not required on Linux. Start your Docker provider before Compose.

Run all commands from the repository root.

## Five-minute quick start

Start only the Redis service needed by catalog serving:

```bash
docker compose -f docker-compose.streaming.yml up -d redis-primary
```

Build the application without running tests:

```bash
mvn package -DskipTests
```

Generate a local recommendation cursor signing key:

```bash
export RECOMMENDATION_CURSOR_SIGNING_KEY="$(openssl rand -hex 32)"
```

Start catalog/recommendation serving:

```bash
env PORT=6010 sh scripts/run-with-jvm-tuning.sh recsys-serving -- \
  mvn exec:java -Dexec.mainClass=com.recsys.api.serving.RecSysServer
```

Keep that terminal open. In another terminal, check the catalog health
endpoint:

```bash
curl --fail http://localhost:6010/health
```

This path uses only tracked classpath data and the Redis primary; it does not
need the ignored model/Spark artifact tree. To stop the Java service, press
`Ctrl-C` in its terminal.

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

### Start the artifact-dependent full stack

Do not use the four-service script until you have restored a compatible model
artifact bundle. The default variant requires `training/feature_config.json`
and compatible companion artifacts under
`src/main/resources/artifacts/model/` or `RECSYS_MODEL_ARTIFACTS_DIR`. Every
configured A/B variant needs a bundle from the same pipeline.

This repository has no acquisition or generation workflow for those files.
Without the required model bundle, model serving **will fail during startup**,
and the gateway aggregate health endpoint will remain `503`.

After supplying the artifacts, start the infrastructure and four services:

```bash
docker compose -f docker-compose.streaming.yml up -d
export RECOMMENDATION_CURSOR_SIGNING_KEY="$(openssl rand -hex 32)"
GATEWAY_ALLOW_ANONYMOUS=true sh scripts/run-microservices-local.sh
```

> **Development only:** anonymous mode deliberately disables gateway
> authentication. Never use it in production. Without anonymous mode, API keys,
> or Cognito configuration, the gateway fails closed at startup. Configure a
> production mechanism using the [Configuration Guide](CONFIG_GUIDE.md).

The script waits ten seconds before the gateway and writes one log per service
under `logs/`. Check the aggregate only after all three backends are ready:

```bash
curl --fail http://localhost:8010/health
```

Press `Ctrl-C` in the script terminal to terminate its four child processes.

### Start one service

Run one command per terminal. Start Redis first for the catalog and online
services. Start all three backends before the gateway if you want the gateway
aggregate health check to return success.

Generate and export one signing key for every catalog and online instance in
the local topology:

```bash
export RECOMMENDATION_CURSOR_SIGNING_KEY="$(openssl rand -hex 32)"
```

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

This model command has the same artifact prerequisite as the full-stack
workflow and will fail without the default variant's model bundle.

API gateway:

```bash
env GATEWAY_PORT=8010 GATEWAY_ALLOW_ANONYMOUS=true \
  sh scripts/run-with-jvm-tuning.sh api-gateway -- \
  mvn exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer
```

The anonymous setting in the gateway command is development-only. The wrapper
loads the repository's checked-in JVM options. Use
`mvn package -DskipTests` for a fast rebuild and the artifact-dependent
all-service script when you do not need process-level isolation.

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

With Redis on `localhost:6379` and `redis-cli` installed:

```bash
sh streaming/online-serving/scripts/load_online_features.sh
```

It loads the checked-in sample; see the
[online-serving guide](streaming/online-serving/README.md) for Flink.

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
clean checkout. Model serving on port `8080` will fail during startup without
the default variant's model bundle. Restore the files from a known-good
pipeline output; this checkout has no artifact-preparation script, and the
`offline-embedding` profile generates only Word2Vec item embeddings. External
bundles can use `RECSYS_MODEL_ARTIFACTS_DIR` for `training/` and `test/` model
variants and `RECSYS_SPARK_ARTIFACTS_DIR` for `als_model_metadata.json`; both
are bound in [application.yml](src/main/resources/application.yml). This README
does not claim that the fixture-dependent suite passes.

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

The clean-clone quick start uses the catalog port and Redis defaults. The
artifact-dependent full-stack command explicitly sets
`GATEWAY_ALLOW_ANONYMOUS=true` for local development; its script supplies the
standard service ports and local gateway upstreams. Catalog, Spring model, and
online recommendation serving also require
`RECOMMENDATION_CURSOR_SIGNING_KEY`; the quick-start and full-stack commands
generate a local key with OpenSSL. Production rotations follow the
[shared-key runbook](docs/runbooks/recommendation-cursor-key-rotation.md).

The local settings most often overridden are:

| Setting | Default | Local use |
|---|---:|---|
| `PORT` | `6010` | Catalog/recommendation service port |
| `ONLINE_DEMO_PORT` | `7010` | Online prediction service port |
| `SERVER_PORT` | `8080` | Spring Boot model-serving port |
| `GATEWAY_PORT` | `8010` | API gateway port |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Host Redis connection |
| `GATEWAY_ALLOW_ANONYMOUS` | `false` | Development-only opt-in used by the artifact-dependent full-stack command |

Do not copy service, resilience, authentication, or deployment variables into
new README tables. The authoritative defaults, parsing behavior, Kubernetes
overrides, and secret-handling guidance are in the
[Configuration Guide](CONFIG_GUIDE.md).

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
unsupported Java version, invalid opt-in configuration, or missing model
artifacts. The catalog service's MySQL configuration is validated only when
MySQL is enabled.

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

[System-design investigations](docs/system_design/) — the numbered investigation
directory. **Every investigation is listed here**; this section is the index, and
`DocumentationIndexTest` fails the build if a doc is added, renamed, or removed
without updating it.

| # | Investigation | Covers |
|---|---|---|
| 01 | [Load Balancing](docs/system_design/01_Load_Balancing.md) | ALB → kube-proxy/topology-aware routing → Armeria health-checked groups, and the capacity-weight feedback signal |
| 02 | [Caching](docs/system_design/02_Caching.md) | Three-tier embedding cache, soft-TTL serve-stale-while-refresh, single-flight snapshots |
| 03 | [DB Scaling & Sharding](docs/system_design/03_DB_Scaling_Sharding.md) | The two Redis sharded stores, versioned topology and online reshard, which scaling lever buys what, and where sharding ends the single-transaction guarantee |
| 04 | [Replication](docs/system_design/04_Replication.md) | Single-primary Redis with AZ-aware read replicas, Sentinel failover, replica-lag probing, cross-region DR |
| 05 | [CAP](docs/system_design/05_CAP.md) | Where each store chooses consistency over availability during a partition, and the tunable dial |
| 06 | [Consistent Hashing](docs/system_design/06_Consistent_Hashing.md) | The shared FNV-1a primitive and the virtual-node ring that maps devices to shards |
| 07 | [Message Queue](docs/system_design/07_Message_Queue.md) | The fire-and-forget bounded queue carrying behavioral/experiment events off the serving path |
| 08 | [Rate Limits](docs/system_design/08_Rate_Limits.md) | One token-bucket primitive, three per-instance limiters, one global cluster limiter |
| 09 | [API Gateway](docs/system_design/09_API_Gateway.md) | Route ownership, authentication, health aggregation, circuit breakers, metrics |
| 10 | [Microservices](docs/system_design/10_MicroServices.md) | Service boundaries, one-image/four-mains deployment model, layering, and why every hop is HTTP/JSON |
| 11 | [Service Discovery](docs/system_design/11_Service_Discovery.md) | Static route table, the opt-in Redis registry, and Cloud Map resolution |
| 12 | [CDN](docs/system_design/12_CDNS.md) | What CloudFront caches, cache-key constraints, origin lockdown, and the local stand-in |
| 13 | [DB Indexing](docs/system_design/13_DB_Indexing.md) | Which secondary indexes exist, and how every query is pinned to its index by contract tests |
| 14 | [Partitioning](docs/system_design/14_Partitioning.md) | The five partition dimensions, and where the shards physically live |
| 15 | [Eventual Consistency](docs/system_design/15_Eventual_Consistency.md) | How eventual consistency manifests and is deliberately bounded per layer |
| 16 | [SSE Streaming](docs/system_design/16_SSE_Streaming.md) | The LLM-proxy SSE passthrough, its lifecycle, and why no WebSockets or gRPC |
| 17 | [Scalability](docs/system_design/17_Scalability.md) | Compute-tier HPA, data-tier levers, and the overload-protection layers that let it scale without collapsing |
| 18 | [Fault Tolerance](docs/system_design/18_Fault_Tolerance.md) | Resilience contracts, graceful drain, failure-path evidence, and status |
| 19 | [Pagination](docs/system_design/19_Pagination.md) | Keyset and offset implementations, and the shared signed live-keyset recommendation contract |
| 20 | [AuthN / AuthZ](docs/system_design/20_AuthN_AuthZ.md) | The six credentials, fail-closed startup, credential stripping, and the operator-token tier |
| 21 | [Observability](docs/system_design/21_Observability.md) | The two-phase split — logs to Splunk, system health to Prometheus — the metric inventory, and the alerts that keep it honest |

Cross-cutting entry points:
- [API versioning](docs/system_design/09_API_Gateway.md#api-versioning-and-deprecation)
  — the gateway-owned `/api/v{n}` path version, why an unversioned `/api` path is
  implicit v1, why `/v2` on the internal services means "different pipeline" rather
  than "next generation", and the `Deprecation`/`Sunset`/`Link` headers. Edge
  cache-key constraints are in
  [12_CDNS §1](docs/system_design/12_CDNS.md#1-what-is-cached-and-what-isnt).
- [API compatibility contract](docs/system_design/09_API_Gateway.md#the-compatibility-contract)
  — what counts as a breaking change, the two-version support window, twelve-month
  deprecation notice, the routes deprecated today, and the `Deprecation` / `Sunset`
  headers clients should watch.

Operational runbooks — **all of them**, same index rule as above:

*Disaster recovery*
- [Regional failover](docs/runbooks/dr-regional-failover.md) — promote the standby region and capture evidence.
- [Regional failback](docs/runbooks/dr-failback.md) — return traffic to the recovered primary region.
- [Data-tier promotion](docs/runbooks/dr-data-tier-promotion.md) — restore the **write** path after DNS failover.
- [DR game day](docs/runbooks/dr-game-day.md) — rehearse and evaluate a regional recovery.
- [Zonal resilience](docs/runbooks/zonal-resilience.md) — surviving the loss of one AZ within a region.

*Edge and CDN*
- [CDN operations](docs/runbooks/cdn-operations.md) — running the CloudFront distribution in front of the gateway.
- [CDN rollback](docs/runbooks/cdn-rollback.md) — the rollout order reversed; skipping ahead strands traffic.
- [CDN local stand-in](docs/runbooks/cdn-local.md) — nginx mirror of the cache behaviors, no AWS account needed.
- [WAF WebACL](docs/runbooks/waf-webacl.md) — the out-of-band WAFv2 ACL Kustomize cannot create.
- [Local ElastiCache stand-in](docs/runbooks/elasticache-local.md) — run the eviction invariant EKS depends on, no AWS account needed.

*Traffic and load*
- [Overload protection](docs/runbooks/overload-protection.md) — overload symptoms, controls, validation, recovery.
- [Overload characterization](docs/runbooks/overload-characterization.md) — the opt-in `@Tag("load")` harnesses and the invariants they lock in.
- [Gateway auth](docs/runbooks/gateway-auth.md) — API keys, Cognito JWT, and the fail-closed startup rule.
- [Splunk HEC logging](docs/runbooks/splunk-hec-logging.md) — shipping structured application logs to Splunk, and the at-most-once limits of what lands there.

*Data and delivery*
- [Durable eventual consistency](docs/runbooks/durable-eventual-consistency.md) — outbox, saga state, relay, consistency tokens, reconciliation.
- [Kafka partition cutover](docs/runbooks/kafka-partition-cutover.md) — moving to a new topic generation without breaking per-user ordering.
- [Cursor key rotation](docs/runbooks/recommendation-cursor-key-rotation.md) — rotating the HMAC signing key behind pagination cursors.
- [Deploy by image digest](docs/runbooks/deploy-image-digest.md) — why the EKS overlay pins an immutable digest.
- [Retire a backend](docs/runbooks/retire-backend.md) — **irreversible**; permanently shuts down all four services and their infrastructure.

**How this index stays honest.** It is complete by construction, not by discipline:
[`DocumentationIndexTest`](src/test/java/com/recsys/docs/DocumentationIndexTest.java)
asserts in both directions — every doc under `docs/system_design/` and
`docs/runbooks/` is linked here, and every `docs/…` link here resolves to a file that
exists. Adding a doc without indexing it fails the build, and so does renaming one
without updating the link. Historical design records under `docs/superpowers/` are
deliberately out of scope; they are point-in-time artifacts, not topics.

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
