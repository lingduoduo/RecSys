# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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
| RecSys Serving API | 6010 | `com.recsys.serving.RecSysServer` |
| Model Serving (Spring Boot) | 8080 | `com.recsys.model.ModelApplication` |
| Online Serving | 7010 | `com.recsys.online.serving.OnlinePredictionServer` |
| API Gateway | 8010 | `com.recsys.microservice.MicroserviceGatewayServer` |

Run an individual service:
```bash
# RecSys Serving API
mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer

# Model Serving (Spring Boot / ONNX)
mvn spring-boot:run

# Online Serving
mvn exec:java -Dexec.mainClass=com.recsys.online.serving.OnlinePredictionServer

# API Gateway
mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer
```

Key env vars: `REDIS_HOST`, `REDIS_PORT`, `PORT`/`ONLINE_DEMO_PORT`/`GATEWAY_PORT`, `SERVER_PORT`.

## Architecture

The system demonstrates two recommendation paths:

**Offline/batch path** — `RecSysServer` (Armeria) uses pre-computed Word2Vec embeddings stored in Redis. Recall is embedding-based (cosine similarity via `CandidateGenerator`); embeddings are seeded from classpath text files at startup if Redis is empty. Routes: `/getrecommendation`, `/similar`, `/setembedding`, `/v1/models/recmodel:predict`.

**Model-based path** — `ModelApplication` (Spring Boot) runs a PyTorch two-tower ONNX model (`dssm_model.onnx` in `src/main/resources/artifacts/`). `RetrievalService` encodes the user tower; `RankingService` scores candidates. Supports variant-aware artifacts for A/B testing (`recsys.ab-test.*` in `application.yml`), result caching, submit-token CSRF protection, and load shedding.

**Online path** — `OnlinePredictionServer` (Armeria) uses Redis-backed `OnlineFeatureStore` (recent history) and `ShardedTopKStore` (trending) to produce real-time recommendations without a neural model. `OnlineLearner` updates lightweight serving parameters from streaming feedback.

**API Gateway** — `MicroserviceGatewayServer` (Armeria) routes to the above services plus an optional LLM explanation endpoint. Has per-route circuit breakers (`RouteCircuitBreaker`), token-bucket rate limiting (`GatewayRateLimiter`), a dedicated LLM proxy with token budgets (`LlmTokenRateLimiter`, `LlmResponseCache`), and 30 s Cloud Map DNS TTL for EKS blue/green deploys.

## Package Map

| Package | Responsibility |
|---|---|
| `serving/` | Armeria HTTP services for the offline RecSys API |
| `modelbased/` | Spring Boot ONNX model serving (controller → service → ONNX runtime) |
| `online/` | Online feature store, learner, rate limiter, distributed lock (Redis), Armeria services |
| `microservice/` | API gateway: proxy, circuit breaker, rate limiter, LLM proxy |
| `features/` | Embedding stores (Redis, local heap, multi-level L1→L2→L3), LSH index, candidate generation |
| `models/` | Shared domain DTOs (Movie, User, Rating) |
| `saga/` | AWS Step Functions saga orchestration (SagaOrchestrator, TCC variant) |
| `mysql/` | Thin MySQL client wrapper |
| `pagination/` | Cursor-based SQL helpers for million-scale result sets |

`streaming/flink/` and `training/rulebased/` are **excluded from the Maven compile** (they need Spark/Flink classpaths) — edit with that in mind.

## Redis Conventions

- `i2vEmb:<id>` — item (movie) embeddings
- `u2vEmb:<id>` — user embeddings
- `topk:<window>` — sharded top-K trending store (windows: `last_hour`, `last_day`, `last_month`)
- Online feature store keys are written by the Flink job (`streaming/flink/OnlineFeatureStreamingJob`)

## JVM Tuning

JVM options live in `jvm.options` (default), `jvm-g1.options` (G1GC), and `jvm-zgc.options` (ZGC). `scripts/run-with-jvm-tuning.sh` selects options by service name. ONNX Runtime requires `-Xshare:off` (already set in Surefire config).

## Kubernetes

`k8s/base/` contains Kustomize manifests for all four services. `k8s/eks/` has EKS-specific patches (IRSA for gateway, Cloud Map, image pull policy).
