# Configuration Guide

This document describes the configuration-file organization and the
build / test / deployment workflows for **recsys-api** (the Recsys Backend
Service).

> **Reality check up front:** pull requests run a deterministic resilience
> gate, while environmental load and Docker suites run on a schedule or by
> manual dispatch. Deployment remains an operator-controlled Kustomize
> workflow; neither CI workflow deploys or changes production traffic.

## Table of Contents

- [Configuration File Organization](#configuration-file-organization)
- [Toolchain Overview](#toolchain-overview)
- [Build, Test & CI Story](#build-test--ci-story)
- [Docker Image Build](#docker-image-build)
- [Kubernetes Deployment](#kubernetes-deployment)
- [Local Development Setup](#local-development-setup)
- [JVM Tuning](#jvm-tuning)
- [Troubleshooting](#troubleshooting)
- [References](#references)

## Configuration File Organization

This is a single-module Maven project (`com.recsys:recsys-api`) that builds
**one** shared image backing four services; each service is selected at runtime
by the `RECSYS_MAIN_CLASS` env var. Configuration is split by tool convention.

### Project root

- [`pom.xml`](pom.xml) — **the single source of truth** for the build: Java 17
  release target, dependency versions (Spring Boot 3.3.4, ONNX Runtime 1.18.0,
  Armeria 1.28.4, AWS SDK 2.25.70), dependency convergence enforcement, the
  Surefire test configuration, and the `resilience`, `offline-embedding`, and
  `streaming-flink` opt-in profiles.
- [`Dockerfile`](Dockerfile) — multi-stage image build (Maven/Corretto build
  stage → jlink glibc JRE on `debian:12-slim` runtime stage). One image, all
  four services.
- [`.dockerignore`](.dockerignore) — trims the build context sent to the Docker
  daemon (excludes `target/`, `src/test/`, `streaming/`, `k8s/`, docs, etc.).
- [`config/jvm/`](config/jvm/) — per-service JVM flag sets, a G1 profile and a
  `-zgc` variant each.
- [`docker-compose.streaming.yml`](docker-compose.streaming.yml) — local
  streaming infrastructure (Zookeeper, Kafka, Flink, Redis) for the streaming
  path. Not part of the service image.

### `config/` directory

- [`config/jvm/`](config/jvm/) — **per-service** JVM option files consumed by
  [`scripts/run-with-jvm-tuning.sh`](scripts/run-with-jvm-tuning.sh):
  `recsys-serving`, `model-serving`, `online-serving`, `api-gateway`,
  `offline-embedding`, each with a `-zgc` variant. These layer service-specific
  tuning on top of the root `jvm-*.options`.

### `docker/` directory

- [`docker/redis/sentinel.conf`](docker/redis/sentinel.conf) — Redis Sentinel
  configuration for the local high-availability Redis topology.

### `k8s/` directory (Kustomize)

- [`k8s/base/`](k8s/base/) — the Kustomize **base**: one Deployment + Service
  per service (`api-gateway`, `model-serving`, `online-serving`,
  `catalog-serving`), plus `namespace`, `configmap`, `redis-cluster`, `pdb`,
  `hpa`, `network-policy`, and `servicemonitor`. The
  [`kustomization.yaml`](k8s/base/kustomization.yaml) declares the image tag
  once via an `images:` transformer (`recsys-backend-service:local`).
- [`k8s/eks/`](k8s/eks/) — the EKS **overlay**: retargets the image to ECR,
  scales the in-cluster Redis to zero (ElastiCache is used instead), and applies
  IRSA / Cloud Map / pull-policy patches.

### `scripts/` directory

- [`run-microservices-local.sh`](scripts/run-microservices-local.sh) — starts
  all four services locally, logs to `logs/<service>.log`.
- [`run-with-jvm-tuning.sh`](scripts/run-with-jvm-tuning.sh) — runs one service
  with its `config/jvm/<profile>.jvmopts` applied.
- [`retire-backend.sh`](scripts/retire-backend.sh) — ordered, irreversible
  backend decommission orchestrator.
- [`summarize-resilience-results.py`](scripts/summarize-resilience-results.py) —
  validates Surefire XML plus a suite-specific measurement sidecar and writes
  schema-v1 resilience evidence.
- [`dr-standby-capacity.sh`](scripts/dr-standby-capacity.sh) — HPA-only,
  evidence-gated standby preparation and read-only cutover/failback checks.
- `load-test/`, `arthas-diagnostics.sh`, `mat-heap-analysis.sh`,
  `summarize-gc-logs.sh` — performance / diagnostics helpers.

### Why this layout

- `pom.xml` stays at the project root — the Maven standard, and where Surefire,
  the compiler, and the Spring Boot plugin read their configuration.
- Runtime-tuning configs (`config/jvm/`, `jvm-*.options`) are grouped so the
  root stays readable while each service can be tuned independently.
- Deployment is Kustomize base + environment overlay, so the same manifests
  drive local (`base`) and EKS (`eks`) with only the overlay differing.

## Toolchain Overview

| Concern | Tool | Configuration |
|---|---|---|
| Build & packaging | Maven | [`pom.xml`](pom.xml) (`maven.compiler.release = 17`) |
| Unit / integration tests | JUnit 5 + Surefire | [`pom.xml`](pom.xml) `maven-surefire-plugin` (`-Xshare:off`, tag groups) |
| Container image | Docker (multi-stage, BuildKit) | [`Dockerfile`](Dockerfile), [`.dockerignore`](.dockerignore) |
| JVM startup | AppCDS + jlink JRE | build-time archive in [`Dockerfile`](Dockerfile) |
| Runtime JVM tuning | JVM option files | [`config/jvm/`](config/jvm/) (local), `JAVA_OPTS` (container) |
| Orchestration / deploy | Kubernetes + Kustomize | [`k8s/base/`](k8s/base/), [`k8s/eks/`](k8s/eks/) |
| Model runtime | ONNX Runtime 1.18.0 | dependency in [`pom.xml`](pom.xml); `dssm_model.onnx` in resources |
| Streaming (local) | Kafka + Flink + Redis | [`docker-compose.streaming.yml`](docker-compose.streaming.yml) |
| Resilience CI | GitHub Actions | `.github/workflows/resilience-pr.yml`, `.github/workflows/resilience-scheduled.yml` |
| Deployment | Operator-controlled | Kustomize overlays and DR runbooks; CI does not deploy |

## Build, Test & CI Story

Pull requests run dependency validation and the deterministic resilience
profile:

```bash
mvn --batch-mode validate
mvn --batch-mode -Presilience test
```

The profile excludes `load` and `docker`, requires no daemon or cloud
credentials, and covers the deterministic circuit, limiter, bulkhead,
degradation, drain, outbox, Saga, and TCC paths. The normal local suite remains:

### Build

```bash
# Compile + package, skipping tests
mvn package -DskipTests
```

The default build **excludes** two source trees that need external classpaths:
`com/recsys/training/rulebased/**` (Spark) and `com/recsys/online/flink/**`
(Flink). Compile those via the opt-in profiles:

```bash
mvn -Poffline-embedding package -DskipTests   # adds Spark ML (Word2Vec) sources
mvn -Pstreaming-flink       package -DskipTests   # adds Flink streaming sources
```

### Test

Surefire runs with `-Xshare:off` (ONNX Runtime appends to the bootstrap
classpath, which disables CDS; turning it off up front keeps output clean) and
**skips two tag groups by default**: `@Tag("load")` and `@Tag("docker")`.

```bash
mvn --batch-mode test                     # all tests EXCEPT load + docker

mvn test -Dtest=RecommendationServiceTest # a single test class

# Match the scheduled workflow selectors:
mvn --batch-mode test -DexcludedGroups=docker -Dgroups=load \
  -Dresilience.evidence.suite=load \
  -Dresilience.evidence.output=target/resilience-measurements-load.json
mvn --batch-mode test -DexcludedGroups=load -Dgroups=docker \
  -Dresilience.evidence.suite=docker \
  -Dresilience.evidence.output=target/resilience-measurements-docker.json
```

The scheduled/manual workflow runs each selector in a fresh job, then combines
that job's isolated Surefire reports with its required measurement sidecar:

```bash
python3 scripts/summarize-resilience-results.py \
  --suite load \
  --reports target/surefire-reports \
  --measurements target/resilience-measurements-load.json \
  --output resilience-evidence-load.json
```

The Docker command uses `--suite docker` and the corresponding Docker sidecar.
Evidence is schema version 1 and fails closed on malformed/empty reports,
invalid measurements, failed tests, or failed invariants. Applicability is
explicit: load evidence covers serving/admission/bulkhead/recall/timeout/drain;
Docker evidence covers the real Redis/Lua boundary. Neither artifact claims the
other suite's measurements.

> **Docker-tagged tests require a running Docker daemon.** The scheduled
> GitHub-hosted Docker job is the canonical environmental run. On macOS with
> Colima, Surefire already sets
> `TESTCONTAINERS_RYUK_DISABLED=true` and
> `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` so the socket
> mounts correctly.

The `excludedGroups` property (default `load,docker`) is the single knob that
governs which groups run — override it as shown above.

## Docker Image Build

One multi-stage [`Dockerfile`](Dockerfile) produces the shared image for all
four services:

- **Build stage** (`maven:3.9-amazoncorretto-17`, glibc): Maven `package` with a
  BuildKit `.m2` cache mount, packs `app-classes.jar`, then `jlink`s a minimal
  glibc JRE (`jdeps`-detected modules + a reflective-safety set).
- **Runtime stage** (`debian:12-slim`, glibc): copies the jlink JRE + exploded
  classpath, installs the glibc C++/OpenMP libs ONNX Runtime needs, regenerates
  an AppCDS archive for faster startup, and runs as the non-root `recsys` user.

```bash
# BuildKit is the default in modern Docker; no buildx plugin needed
docker build -t recsys-backend-service:local .

# Run a specific service by overriding the default main class
docker run --rm -e RECSYS_MAIN_CLASS=com.recsys.api.rest.ModelApplication \
  -p 8080:8080 recsys-backend-service:local
```

The default `RECSYS_MAIN_CLASS` is the API gateway
(`com.recsys.api.gateway.MicroserviceGatewayServer`); Kubernetes overrides it
per service. `EXPOSE 8010` documents the gateway port only — other services
listen on their own ports (see the table below).

| Service | Main class | Port |
|---|---|---|
| RecSys / catalog serving | `com.recsys.api.serving.RecSysServer` | 6010 |
| Model serving (Spring Boot / ONNX) | `com.recsys.api.rest.ModelApplication` | 8080 |
| Online serving | `com.recsys.api.online.OnlinePredictionServer` | 7010 |
| API gateway | `com.recsys.api.gateway.MicroserviceGatewayServer` | 8010 |

## Kubernetes Deployment

Deployment is Kustomize. The **base** is environment-agnostic; the **eks**
overlay layers cloud specifics on top.

```bash
# Render locally (uses `kubectl kustomize`; a standalone `kustomize` binary
# is not required)
kubectl kustomize k8s/base            # base (image recsys-backend-service:local)
kubectl kustomize k8s/eks             # EKS overlay (ECR image, ElastiCache, IRSA)

# Apply
kubectl apply -k k8s/base
kubectl apply -k k8s/eks
```

What the `eks` overlay ([`k8s/eks/kustomization.yaml`](k8s/eks/kustomization.yaml))
changes versus base:

- **Image** → `…dkr.ecr.us-east-1.amazonaws.com/recsys-backend-service:latest`
  with an `Always` pull policy (so ECR tag reassignment is picked up on rollout).
- **In-cluster Redis** scaled to `0` (primary / replica / sentinel) — EKS uses
  ElastiCache via the `redis-elasticache-patch`.
- **IRSA** ServiceAccount for the gateway, **Cloud Map** service registration +
  private-DNS env patches.

To deploy a new build, bump the image tag in **one** place — the overlay's
`images:` block (`newTag:`), or `kubectl edit`/`kustomize edit set image` — since
the tag is centralized by the transformer rather than hardcoded in each manifest.

Rollout behaviour is tuned in the base Deployments: startup / readiness /
liveness probes on every service, a zero-downtime rolling strategy pinned on
model-serving (`maxUnavailable: 0, maxSurge: 2`) while the others use the
default rolling update, PodDisruption budgets
([`pdb.yaml`](k8s/base/pdb.yaml)), and autoscaling
([`hpa.yaml`](k8s/base/hpa.yaml)).

## Local Development Setup

```bash
# Build (skip tests for speed)
mvn package -DskipTests

# Start all four services locally (logs -> logs/<service>.log)
sh scripts/run-microservices-local.sh

# Or run one service with its tuned JVM profile:
sh scripts/run-with-jvm-tuning.sh model-serving -- mvn spring-boot:run
sh scripts/run-with-jvm-tuning.sh api-gateway   -- \
  mvn exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer

# Bring up local streaming infra (Kafka, Flink, Redis)
docker-compose -f docker-compose.streaming.yml up
```

Key environment variables: `REDIS_HOST`, `REDIS_PORT`,
`PORT` / `ONLINE_DEMO_PORT` / `GATEWAY_PORT`, `SERVER_PORT`,
`RECSYS_MAIN_CLASS`, `RECALL_CHANNEL_TIMEOUT_MS` (per-channel recall timeout,
default 200).

### Online Redis emergency limiter

The Redis-backed online limiter is the cluster-wide authority while Redis is
healthy. If Redis fails, returns a malformed decision, or its circuit does not
admit the call, each replica uses one local emergency token bucket:

| Variable | Default | Contract |
|---|---:|---|
| `ONLINE_REDIS_EMERGENCY_LIMIT_ENABLED` | `true` | Must be exactly `true` or `false` (case-insensitive). |
| `ONLINE_REDIS_EMERGENCY_RATE_PER_SECOND` | one quarter of `ONLINE_REDIS_RATE_LIMIT_QPS`, minimum `1` | Finite, non-negative number. Kubernetes sets `50`. |
| `ONLINE_REDIS_EMERGENCY_BURST` | emergency rate rounded down, minimum `1` | Non-negative integer. Kubernetes sets `50`. |

These settings are inactive when `ONLINE_REDIS_RATE_LIMIT_QPS=0`. Invalid
values fail service startup instead of silently widening the degraded path.
For an emergency rollback, set the enable flag to `false`, or set either rate
or burst to `0`; Redis failures then retain the former unlimited fail-open
behavior. The budget is **per online-serving replica**, is consumed only on the
Redis fail-open path, and therefore is not a cluster-wide limit. Exhaustion
uses the existing `429`, positive `Retry-After`, and
`online serving rate limited` response.

Prometheus exposes `recsys_online_rate_limit_decisions_total` with only bounded
`source=redis|emergency` and `result=allowed|rejected` tags. `/online/ops`
reports the circuit state, emergency configuration, and the four cumulative
decision counters without bucket or principal dimensions.

## JVM Tuning

Two places JVM flags come from, depending on how the service runs:

1. **Locally** — per-service flag sets in [`config/jvm/`](config/jvm/), selected
   by [`scripts/run-with-jvm-tuning.sh <profile>`](scripts/run-with-jvm-tuning.sh),
   which exports them as `MAVEN_OPTS`. Each service has a G1 profile and a
   `-zgc` variant, e.g. `model-serving.jvmopts` / `model-serving-zgc.jvmopts`.
2. **In a container** — `JAVA_OPTS`, expanded by the image entrypoint and set
   per service in `k8s/base/*.yaml` (`256 m`–`2 g`, plus
   `-XX:MaxDirectMemorySize=512m` for model-serving). The `config/jvm/` files
   are *not* baked into the image.

Note: ONNX Runtime requires `-Xshare:off` (already set in the Surefire config
for tests). In production, the image's AppCDS archive is loaded via
`-XX:SharedArchiveFile … -Xshare:auto`, which silently falls back if the archive
is incompatible.

## Troubleshooting

- **A test class doesn't run** — it may be tagged `@Tag("load")` or
  `@Tag("docker")`, which are excluded by default. Use the exact scheduled
  selectors above so the matching evidence probe and sidecar run as well.
- **Docker/Testcontainers tests fail to find the daemon** — ensure Docker is
  running. On macOS/Colima the Surefire socket overrides are already set; for
  other setups export `DOCKER_HOST` / `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
  to your daemon socket.
- **Model serving crashes on startup with `UnsatisfiedLinkError`** — ONNX
  Runtime's native lib is glibc-built; run it on the `debian:12-slim` image (the
  current `Dockerfile`), not an alpine/musl base.
- **Flink or Spark sources fail to compile** — they're excluded from the default
  build on purpose; use `-Pstreaming-flink` or `-Poffline-embedding`.
- **A new image tag isn't picked up on EKS** — the overlay sets `Always` pull
  policy, but you must also point the overlay's `images: newTag:` at the new tag
  (or push over `latest`).

## References

- [`README.md`](README.md) — full architecture, service usage, and Redis
  conventions.
- [`.claude/CLAUDE.md`](.claude/CLAUDE.md) — build/test commands, package map,
  ports, and Redis key conventions for contributors and agents.
- [`pom.xml`](pom.xml) — build, test groups, dependency versions, and profiles.
- [`Dockerfile`](Dockerfile) — the multi-stage image build.
- [`k8s/base/`](k8s/base/) / [`k8s/eks/`](k8s/eks/) — Kustomize base and EKS
  overlay.
- `docs/superpowers/specs/` and `docs/superpowers/plans/` — design specs and
  implementation plans, including the Docker image optimization and deployment
  simplification work referenced above.
