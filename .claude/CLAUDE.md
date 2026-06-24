# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Build (skip tests)
mvn package -DskipTests

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=RecommendationServiceTest

# Run load tests (opt-in; excluded by default)
mvn test -DexcludedGroups="" -Dgroups=load

# Start all four services locally (logs go to logs/<service>.log)
sh scripts/run-microservices-local.sh

# Start streaming infrastructure (Kafka, Flink, Redis) via Docker
docker-compose -f docker-compose.streaming.yml up
```

## Services & Ports

| Service | Port | Entry point |
|---|---|---|
| RecSys Serving API | 6010 | `com.recsys.api.serving.RecSysServer` |
| Model Serving (Spring Boot) | 8080 | `com.recsys.api.rest.ModelApplication` |
| Online Serving | 7010 | `com.recsys.api.online.OnlinePredictionServer` |
| API Gateway | 8010 | `com.recsys.api.gateway.MicroserviceGatewayServer` |

Run an individual service:
```bash
# RecSys Serving API
mvn exec:java -Dexec.mainClass=com.recsys.api.serving.RecSysServer

# Model Serving (Spring Boot / ONNX)
mvn spring-boot:run

# Online Serving
mvn exec:java -Dexec.mainClass=com.recsys.api.online.OnlinePredictionServer

# API Gateway
mvn exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer
```

Key env vars: `REDIS_HOST`, `REDIS_PORT`, `PORT`/`ONLINE_DEMO_PORT`/`GATEWAY_PORT`, `SERVER_PORT`, `RECALL_CHANNEL_TIMEOUT_MS` (per-channel recall timeout for both serving ports; default 200).

## Architecture

The system demonstrates two recommendation paths:

**Offline/batch path** — `RecSysServer` (Jetty) uses pre-computed Word2Vec embeddings stored in Redis. Recall is embedding-based (cosine similarity via `CandidateGenerator`); embeddings are seeded from classpath text files at startup if Redis is empty. Routes: `/getrecommendation`, `/similar`, `/setembedding`, `/v1/models/recmodel:predict`.

**Model-based path** — `ModelApplication` (Spring Boot) runs a PyTorch two-tower ONNX model (`dssm_model.onnx` in `src/main/resources/artifacts/`). `RetrievalService` encodes the user tower; `RankingService` scores candidates. Supports variant-aware artifacts for A/B testing (`recsys.ab-test.*` in `application.yml`), result caching, submit-token CSRF protection, and load shedding.

**Online path** — `OnlinePredictionServer` (Jetty) uses Redis-backed `OnlineFeatureStore` (recent history) and `ShardedTopKStore` (trending) to produce real-time recommendations without a neural model. `OnlineLearner` updates lightweight serving parameters from streaming feedback. Shard topology is operator-triggered via `POST /shards/topology` (header `X-Admin-Token`).

**API Gateway** — `MicroserviceGatewayServer` (Jetty) routes to the above services plus an optional LLM explanation endpoint. Has per-route circuit breakers (`RouteCircuitBreaker`), token-bucket rate limiting (`GatewayRateLimiter`), a dedicated LLM proxy with token budgets (`LlmTokenRateLimiter`, `LlmResponseCache`), and 30 s Cloud Map DNS TTL for EKS blue/green deploys.

## Package Map

The code is organized into clean-architecture layers under `com.recsys`; the package
advertises a class's *role*, not the service that uses it. Each layer has feature
sub-packages.

| Layer | Responsibility |
|---|---|
| `api/` | Transport / entry points: `serving` (offline Jetty), `online` (Jetty), `gateway` (Jetty), `rest` (Spring Boot app + controllers), `request`, `response`, `converter`, `envelope` |
| `application/` | Use-case orchestration: `recommendation`, `retrieval` (recall channels/coldstart/multichannel), `ranking`, `feature`, `experiment` (A/B), `auth`, `model` (ONNX pipeline/artifacts), `online`, `gateway` (proxy/LLM-proxy), `knowledge`, `pagination`, `saga` |
| `domain/` | Domain value types: `item`, `user`, `rating`, `recommendation`, `prediction`, `online`, `knowledge`, `saga` |
| `infrastructure/` | Technical adapters: `redis` (+ `sharding`), `cache`, `vectordb`, `store`, `messaging`, `persistence` (MySQL), `lock`, `featureflags`, `dataloading`, `resilience` (bloom/hotkey/single-flight), `alb`, `autoscaling` |
| `metrics/` | Request/inference metrics services (Micrometer + Armeria online) |
| `jvm/` | JVM/GC monitors (`GcEventTracker`, `JvmMemoryMonitor`) |
| `tracing/` | `TraceIdAspect` (trace-id propagation) |
| `ratelimit/` | Token-bucket + Redis rate limiters (`TokenBucket`, gateway/LLM/model/Redis) |
| `loadshed/` | Load shedders, admission control, graceful shutdown |
| `resilience/` | Circuit breaker, bulkhead, fault injector (request-tier fault tolerance) |
| `health/` | Online-serving health/ops endpoints + capacity sizing |
| `config/` | Spring config + `@ConfigurationProperties`, `EnvConfig`/`EnvVars`, `NeedLogin` |
| `exception/` | Exception types + `GlobalExceptionHandler` (saga exceptions live in `domain/saga`) |

`online/flink/` and `training/rulebased/` are **excluded from the Maven compile** (they need Spark/Flink classpaths) and are intentionally left outside the layer scheme — edit with that in mind.

## Redis Conventions

- `i2vEmb:<id>` — item (movie) embeddings
- `u2vEmb:<id>` — user embeddings
- `topk:<window>` — sharded top-K trending store (windows: `last_hour`, `last_day`, `last_month`)
- Online feature store keys are written by the Flink job (`streaming/flink/OnlineFeatureStreamingJob`)
- `shard:topology` — authoritative versioned shard-topology snapshot (JSON); instances refresh every 30s
- `sr:rec:{shard}:{seq}` / `sr:dev:{shard}:{id}` / `sr:stream:{shard}` / `sr:seq:{shard}` — generation 1 (unversioned)
- `sr:g{version}:rec:…` etc. — generation ≥2 keys after a reshard; reads dual-read the previous generation for one max-TTL window
- Shard-level reads (`GET /shards/shard`, `readAllShards`) are generation-current — during a migration window they do not dual-read the previous generation (device reads do).

## JVM Tuning

JVM options live in `jvm.options` (default), `jvm-g1.options` (G1GC), and `jvm-zgc.options` (ZGC). `scripts/run-with-jvm-tuning.sh` selects options by service name. ONNX Runtime requires `-Xshare:off` (already set in Surefire config).

## Kubernetes

`k8s/base/` contains Kustomize manifests for all four services. `k8s/eks/` has EKS-specific patches (IRSA for gateway, Cloud Map, image pull policy).
