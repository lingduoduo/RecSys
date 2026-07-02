# Configuration Guide

This document describes the configuration-file organization and the
build / test / deployment workflows for **recsys-api** (the Recsys Backend
Service).

> **Reality check up front:** this repository has **no GitHub Actions CI
> pipeline**. `.github/` contains only local Java-upgrade tooling, not
> workflows. "CI/CD" here means a local Maven build + test cycle, a
> multi-stage Docker image, and Kustomize deploy overlays — all run by hand
> or from your own automation. Where the toolchain is deliberately manual,
> this guide says so rather than implying an automated gate exists.

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
  Armeria 1.28.4, AWS SDK 2.25.70), the Surefire test configuration, and the
  `offline-embedding` / `streaming-flink` opt-in profiles.
- [`Dockerfile`](Dockerfile) — multi-stage image build (Maven/Corretto build
  stage → jlink glibc JRE on `debian:12-slim` runtime stage). One image, all
  four services.
- [`.dockerignore`](.dockerignore) — trims the build context sent to the Docker
  daemon (excludes `target/`, `src/test/`, `streaming/`, `k8s/`, docs, etc.).
- [`jvm.options`](jvm.options), [`jvm-g1.options`](jvm-g1.options),
  [`jvm-zgc.options`](jvm-zgc.options) — baseline JVM flag sets (default, G1GC,
  ZGC).
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
| Runtime JVM tuning | JVM option files | [`jvm-*.options`](jvm.options), [`config/jvm/`](config/jvm/) |
| Orchestration / deploy | Kubernetes + Kustomize | [`k8s/base/`](k8s/base/), [`k8s/eks/`](k8s/eks/) |
| Model runtime | ONNX Runtime 1.18.0 | dependency in [`pom.xml`](pom.xml); `dssm_model.onnx` in resources |
| Streaming (local) | Kafka + Flink + Redis | [`docker-compose.streaming.yml`](docker-compose.streaming.yml) |
| CI/CD automation | **none** | no `.github/workflows/`; build/test/deploy are manual |

## Build, Test & CI Story

There is **no automated CI pipeline** in this repo (no `.github/workflows/`).
The build and test steps below are what a CI job *would* run — invoke them
locally or wire them into your own runner.

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
mvn test                                  # all tests EXCEPT load + docker

mvn test -Dtest=RecommendationServiceTest # a single test class

# Opt in to the tag-gated groups:
mvn test -DexcludedGroups="" -Dgroups=load   # load tests only
mvn test -DexcludedGroups=load               # include docker tests (keep load excluded)
```

> **Docker-tagged tests are local-only.** They use Testcontainers and require a
> running Docker daemon; because there is no CI, they run **only** when you
> invoke them locally. On macOS with Colima, Surefire already sets
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

## JVM Tuning

Two layers of JVM configuration:

1. **Baseline flag sets** at the root: [`jvm.options`](jvm.options) (default),
   [`jvm-g1.options`](jvm-g1.options) (G1GC),
   [`jvm-zgc.options`](jvm-zgc.options) (ZGC).
2. **Per-service overrides** in [`config/jvm/`](config/jvm/), selected by
   [`scripts/run-with-jvm-tuning.sh <profile>`](scripts/run-with-jvm-tuning.sh).
   Each service has a G1 and a `-zgc` variant, e.g. `model-serving.jvmopts` /
   `model-serving-zgc.jvmopts`.

Note: ONNX Runtime requires `-Xshare:off` (already set in the Surefire config
for tests). In production, the image's AppCDS archive is loaded via
`-XX:SharedArchiveFile … -Xshare:auto`, which silently falls back if the archive
is incompatible.

## Troubleshooting

- **A test class doesn't run** — it may be tagged `@Tag("load")` or
  `@Tag("docker")`, which are excluded by default. Run with
  `-DexcludedGroups=load` (docker) or `-Dgroups=load -DexcludedGroups=""` (load).
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
