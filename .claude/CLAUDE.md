# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

Requires JDK 17 (e.g. `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`). On newer JDKs (25), the default
toolchain fails a clean compile of two pre-existing files (`LlmResponseCache.java`, `RecommendationCache.java`)
due to stricter generics handling of a diamond-operator-with-anonymous-class.

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

Key env vars: `REDIS_HOST`, `REDIS_PORT`, `PORT`/`ONLINE_DEMO_PORT`/`GATEWAY_PORT`, `SERVER_PORT`, `RECALL_CHANNEL_TIMEOUT_MS` (per-channel recall timeout for both serving ports; default 200), `LLM_CONNECT_TIMEOUT_MS` (default 2000), `LLM_IDLE_TIMEOUT_MS` (default 60000), `LLM_PING_INTERVAL_MS` (default 20000, HTTP/2 keepalive) — these three tune the gateway's dedicated LLM `ClientFactory`. Keep `LLM_PING_INTERVAL_MS` below `LLM_IDLE_TIMEOUT_MS`. `GATEWAY_UPSTREAM_HEALTHCHECK_ENABLED` (default true; the gateway data path wraps each upstream in a health-checked Armeria endpoint group so a down backend is dropped from selection and requests fast-fail with 503 instead of hanging — set false to disable probing, e.g. local dev without all backends running) and `GATEWAY_UPSTREAM_HEALTHCHECK_INTERVAL_MS` (default 10000) tune upstream health checking. Host resolution and the 30 s Cloud Map DNS cache are unchanged. `SERVICE_REGISTRY_ENABLED` (default false; opt-in Redis-backed service registry — backends self-register their advertised address with a heartbeat and the gateway resolves upstreams from it, falling back to the static route address when a service is unregistered or Redis is unavailable; the gateway opens no Redis connection when off), `SERVICE_REGISTRY_HEARTBEAT_MS` (default 10000), `SERVICE_REGISTRY_TTL_MS` (default 30000), `SERVICE_REGISTRY_REFRESH_MS` (gateway poll, default 10000), and per-service `SERVICE_REGISTRY_SERVICE_NAME` / `SERVICE_REGISTRY_ADVERTISE_URL`.
`CATALOG_MAX_CONCURRENT_REQUESTS`/`CATALOG_DRAIN_UTILIZATION` (RecSys 6010 admission control),
`RECALL_BULKHEAD_QUEUE_CAPACITY` (bounded recall queue on 6010/7010). Overload-protection layers
are documented in `docs/runbooks/overload-protection.md`.

## Architecture

The system demonstrates two recommendation paths:

**Offline/batch path** — `RecSysServer` (Armeria) uses pre-computed Word2Vec embeddings stored in Redis. Recall is embedding-based (cosine similarity via `CandidateGenerator`); embeddings are seeded from classpath text files at startup if Redis is empty. Routes: `/getrecommendation`, `/similar`, `/setembedding`, `/v1/models/recmodel:predict`.

**Model-based path** — `ModelApplication` (Spring Boot) runs a PyTorch two-tower ONNX model (`dssm_model.onnx` in `src/main/resources/artifacts/`). `RetrievalService` encodes the user tower; `RankingService` scores candidates. Supports variant-aware artifacts for A/B testing (`recsys.ab-test.*` in `application.yml`), result caching, submit-token CSRF protection, and load shedding.

**Online path** — `OnlinePredictionServer` (Armeria) uses Redis-backed `OnlineFeatureStore` (recent history) and `ShardedTopKStore` (trending) to produce real-time recommendations without a neural model. `OnlineLearner` updates lightweight serving parameters from streaming feedback. Shard topology is operator-triggered via `POST /shards/topology` (header `X-Admin-Token`).

**API Gateway** — `MicroserviceGatewayServer` (Armeria) routes to the above services plus an optional LLM explanation endpoint. Has per-route circuit breakers (`RouteCircuitBreaker`), token-bucket rate limiting (`GatewayRateLimiter`), a dedicated LLM proxy with token budgets (`LlmTokenRateLimiter`, `LlmResponseCache`), and 30 s Cloud Map DNS TTL for EKS blue/green deploys.

## Package Map

The code is organized into clean-architecture layers under `com.recsys`; the package
advertises a class's *role*, not the service that uses it. Each layer has feature
sub-packages.

| Layer | Responsibility |
|---|---|
| `api/` | Transport / entry points: `serving` (offline Armeria), `online` (Armeria), `gateway` (Armeria), `rest` (Spring Boot app + controllers), `request`, `response`, `converter`, `envelope` |
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
- `svc:registry:<serviceName>` — opt-in service registry (`SERVICE_REGISTRY_ENABLED`): advertised address string, TTL-renewed by each backend's heartbeat (all four services register — the Armeria ones directly, the Spring model service via `ServiceRegistryConfig`); liveness = key present, gateway MGETs known services and falls back to static route addresses. When enabled, the gateway `/health` response includes a `registry` section reporting each service's resolution `source` (`registry` vs `static` fallback) and the snapshot age.
- `sr:rec:{shard}:{seq}` / `sr:dev:{shard}:{id}` / `sr:stream:{shard}` / `sr:seq:{shard}` — generation 1 (unversioned)
- `sr:g{version}:rec:…` etc. — generation ≥2 keys after a reshard; reads dual-read the previous generation for one max-TTL window
- Shard-level reads (`GET /shards/shard`, `readAllShards`) are generation-current — during a migration window they do not dual-read the previous generation (device reads do).

## JVM Tuning

JVM options live in `jvm.options` (default), `jvm-g1.options` (G1GC), and `jvm-zgc.options` (ZGC). `scripts/run-with-jvm-tuning.sh` selects options by service name. ONNX Runtime requires `-Xshare:off` (already set in Surefire config).

## Kubernetes

`k8s/base/` contains Kustomize manifests for all four services. `k8s/eks-shared/`
is a Kustomize *component* holding the region-agnostic EKS patches (IRSA, Cloud
Map, topology-aware routing, gateway ClusterIP, in-cluster Redis → 0). Each region
overlay composes `../base` + `../eks-shared` and overrides only region-specific
values:

- `k8s/eks/` — **us-east-1** (primary).
- `k8s/eks-us-west-2/` — **us-west-2** warm-standby DR (reduced HPA minReplicas,
  us-west-2 ECR/ElastiCache/WAF, `AWS_REGION=us-west-2`).

`scripts/set-eks-image-digest.sh` pins the identical digest into both overlays
(ECR cross-region replication). DR operations are documented in
`docs/runbooks/dr-*.md`; the design is
`docs/superpowers/specs/2026-07-08-multi-region-dr-failover-design.md`.

Single-AZ failure resilience (pod spread, AZ-aware Redis reads, PDB tuning) is
documented in `docs/runbooks/zonal-resilience.md`.
