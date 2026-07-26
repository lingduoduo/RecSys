# README Content Migration Inventory

This inventory covers every H2 and H3 from the pre-rewrite README. `KEEP` items
remain only as concise contributor workflow material in Task 3; deep technical
detail is owned by the destination shown here. `MERGE` records a verified owner
update made with this inventory. `REMOVE` applies to volatile examples,
historical optimization logs, or non-contributor detail that has no current
authoritative document to preserve it.

| README section | Disposition | Destination | Verification |
|---|---|---|---|
| `## Architecture Layers` | LINK | `docs/system_design/09_API_Gateway.md`; `10_MicroServices.md`; `03_DB_Sharding.md`; `04_Replication.md`; `15_Eventual_Consistency.md`; `17_Scalability.md`; `18_Fault_Tolerance.md` | Concrete subsystem owners reviewed; contributor README keeps only a documentation map. |
| `### System Design` | MERGE | `docs/system_design/08_Rate_Limits.md`; `01_Load_Balancing.md`; `02_Caching.md`; `03_DB_Sharding.md`; `04_Replication.md`; `09_API_Gateway.md`; `15_Eventual_Consistency.md`; `17_Scalability.md`; `18_Fault_Tolerance.md` | The unique Redis emergency-limiter correction was merged into `08`; the remaining design concepts already have these direct owners. |
| `## Quick Start` | KEEP | README contributor workflow | `docker-compose.streaming.yml`; `scripts/run-microservices-local.sh`; service entry points. |
| `### Start individual services` | KEEP | README contributor workflow | `RecSysServer`, `OnlinePredictionServer`, `ModelApplication`, and `MicroserviceGatewayServer`. |
| `## Contents` | REMOVE | — | Replaced by the concise Task 3 structure. |
| `## Services & Ports` | KEEP | README contributor workflow | `docs/system_design/10_MicroServices.md`; four service entry points. |
| `## Recommendation Flow` | LINK | `docs/system_design/10_MicroServices.md` | `MultiChannelRecallService`; `RecommendationOrchestrator`. |
| `## API Reference` | LINK | `docs/system_design/10_MicroServices.md` | Service ownership and health surfaces are verified there; volatile API inventory is removed. |
| `### Port 6010 — Catalog & Recommendation Serving` | LINK | `docs/system_design/10_MicroServices.md` | `src/main/java/com/recsys/api/serving/RecSysServer.java`. |
| `### Shared recall core` | LINK | `docs/system_design/10_MicroServices.md` | `src/main/java/com/recsys/application/retrieval/multichannel/MultiChannelRecallService.java`. |
| `### Port 7010 — Online Prediction Server (Feature Store)` | LINK | `docs/system_design/10_MicroServices.md` | `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`. |
| `### Port 8080 — Model Serving (Spring Boot)` | LINK | `docs/system_design/10_MicroServices.md` | `src/main/java/com/recsys/api/rest/ModelApplication.java`. |
| `### Port 8010 — API Gateway` | LINK | `docs/system_design/09_API_Gateway.md` | `src/main/java/com/recsys/api/gateway/MicroserviceGatewayServer.java`. |
| `## SQL Use Cases` | LINK | `docs/system_design/13_DB_Indexing.md` | `MillionScalePaginationSql`; `MySqlIndexContractTest`. |
| `### MySQL Index Inventory` | LINK | `docs/system_design/13_DB_Indexing.md` | `src/test/java/com/recsys/infrastructure/persistence/MySqlIndexContractTest.java`. |
| `### Frontend UI Use Cases` | REMOVE | — | No frontend is in this repository; examples are not a contributor workflow. |
| `### Backend Curl Services` | REMOVE | — | Duplicated volatile API examples; service/health entry points remain in the contributor guide. |
| `### SQL Backend Patterns` | LINK | `docs/system_design/13_DB_Indexing.md` | `MillionScalePaginationSql`; `MovieCatalogRepositoryTest`; Docker index integration test. |
| `## Microservice Gateway` | LINK | `docs/system_design/09_API_Gateway.md` | `MicroserviceGatewayServer`; `MicroserviceRouteTable`. |
| `### Start` | KEEP | README contributor workflow | `scripts/run-microservices-local.sh`; `scripts/run-with-jvm-tuning.sh`. |
| `## CDN Edge` | LINK | `docs/system_design/12_CDNS.md`; `docs/runbooks/cdn-operations.md` | `scripts/create-cdn-distribution.sh`; `docker-compose.cdn.yml`. |
| `## Service Registry` | LINK | `docs/system_design/11_Service_Discovery.md` | `ServiceRegistrar`; `RegistryBackedUpstreams`. |
| `## Fault Tolerance` | LINK | `docs/system_design/18_Fault_Tolerance.md` | Resilience implementation and contract tests cited by the owner. |
| `## Configuration` | LINK | `CONFIG_GUIDE.md` | Task 2 owns the consolidated environment-variable reference. |
| `### Catalog / Recommendation Serving (port 6010)` | LINK | `CONFIG_GUIDE.md` | `EnvConfig` and catalog startup configuration. |
| `### Online Prediction Server (port 7010)` | LINK | `CONFIG_GUIDE.md` | `OnlinePredictionServer`; `EnvConfig`. |
| `### API Gateway (port 8010)` | LINK | `CONFIG_GUIDE.md` | `MicroserviceGatewayServer`; `EnvVars`. |
| `### Model Serving — Spring Boot (port 8080)` | LINK | `CONFIG_GUIDE.md` | Spring `@ConfigurationProperties` and `ModelApplication`. |
| `## Project Layout` | KEEP | README contributor workflow | Repository package tree and `CLAUDE.md` Package Map. |
| `## Model Serving Demo` | REMOVE | — | Artifact walkthrough is not required for the local contributor path. |
| `### Expected artifact layout` | REMOVE | — | Demo-specific artifact detail; no current contributor owner. |
| `### Generate item embeddings with Spark Word2Vec` | REMOVE | — | Optional offline workflow outside the default Maven build. |
| `## A/B Testing` | REMOVE | — | Deep product behavior is outside the contributor entry point. |
| `## Feature Flags` | REMOVE | — | Deep product behavior is outside the contributor entry point. |
| `## Testing` | KEEP | README contributor workflow | `pom.xml` profiles and deterministic Maven commands. |
| `## Redis Test Data` | KEEP | README contributor workflow | Local data-loading scripts and Redis-backed service startup. |
| `## Online Serving` | LINK | `streaming/online-serving/README.md` | Streaming compose file and scripts under `streaming/online-serving/`. |
| `## Sharded Record Store` | LINK | `docs/system_design/03_DB_Sharding.md` | `ShardedRecordStore`; `ShardedRecordStoreDualReadTest`; `ShardedRecordServiceReshardTest`. |
| `## Redis Read Replicas` | LINK | `docs/system_design/04_Replication.md` | `RedisReadReplicaRouter`; `RoutingRedisExecutor`. |
| `## Event Publishers (Message Queues)` | LINK | `docs/system_design/07_Message_Queue.md` | `AsyncEventPublisher`; Kafka and SQS publishers. |
| `## Durable Eventual Consistency` | LINK | `docs/system_design/15_Eventual_Consistency.md`; `docs/runbooks/durable-eventual-consistency.md` | `DurableEventPublisher`; `OutboxRelay`; consistency contract tests. |
| `### Transactional outbox → Kafka/SQS` | LINK | `docs/system_design/15_Eventual_Consistency.md`; `docs/runbooks/durable-eventual-consistency.md` | `DurableEventPublisher`; `OutboxRelay`. |
| `### Read-your-writes: bounded primary reads` | LINK | `docs/system_design/15_Eventual_Consistency.md` | `ConsistencyWaiter`; `RedisLineageReader`; `OnlineServices`. |
| `### Deterministic, atomic Redis writes` | LINK | `docs/system_design/15_Eventual_Consistency.md` | Flink Redis sink Lua write path and consistency tests. |
| `### Reconciliation` | LINK | `docs/runbooks/durable-eventual-consistency.md` | Reconciliation command and Kubernetes CronJob manifests. |
| `### Metrics` | LINK | `docs/system_design/15_Eventual_Consistency.md`; `docs/runbooks/durable-eventual-consistency.md` | Online and relay metrics surfaces. |
| `### Configuration` | LINK | `CONFIG_GUIDE.md` | Task 2 owns durable-event settings. |
| `## Load Balancing` | LINK | `docs/system_design/01_Load_Balancing.md` | `ApplicationLoadBalancer`; `LoadShedder`; `OnlineLoadShedder`. |
| `## Offline Item Embeddings` | REMOVE | — | Optional offline model detail is not part of the local contributor path. |
| `## Kubernetes & EKS` | LINK | `docs/system_design/17_Scalability.md`; `docs/system_design/18_Fault_Tolerance.md`; runbooks | `k8s/base`; `k8s/eks`; DR scripts and manifests. |
| `## Capacity Planning` | LINK | `docs/system_design/17_Scalability.md` | `OnlineCapacityService`; `CapacityController`; `k8s/base/hpa.yaml`. |
| `## JVM Tuning` | REMOVE | — | Optional operational tuning detail; local startup retains the verified standard commands. |
| `## Pipeline Optimizations` | REMOVE | — | Historical optimization log; current subsystem owners remain authoritative. |
| `## LLM Gateway` | LINK | `docs/system_design/09_API_Gateway.md`; `docs/system_design/16_SSE_Streaming.md` | `LlmProxyService`; gateway route wiring. |
| `### Streaming (SSE) vs. buffered` | LINK | `docs/system_design/16_SSE_Streaming.md` | `LlmProxyService`; `LlmProxyServiceTest`. |
| `## Model Rate Limiting` | LINK | `docs/system_design/08_Rate_Limits.md` | `ModelRateLimiter`; `RecommendationController`; `ModelRateLimiterTest`. |
| `## AWS Saga Orchestration` | MERGE | `docs/system_design/15_Eventual_Consistency.md` | `SagaOrchestrators`; `AwsStepFunctionsSagaDefinition`; `AwsTccStepFunctionsSagaDefinition`; their unit tests. |
| `## Developer Notes` | REMOVE | — | Duplicated architecture narrative rather than a contributor workflow. |
| `### Data loading` | REMOVE | — | Implementation detail superseded by local workflow and source code. |
| `### Retrieval strategies` | REMOVE | — | Algorithm narrative is outside the contributor entry point. |
| `### Hot-key and cache controls` | LINK | `docs/system_design/02_Caching.md` | `HotKeyDetector`; embedding-cache implementations. |
| `### Online learner` | REMOVE | — | Internal behavior is outside the contributor entry point. |
| `### Model runtime provider` | REMOVE | — | Internal behavior is outside the contributor entry point. |

## Consolidated owner updates

- `docs/system_design/08_Rate_Limits.md` now records the bounded emergency bucket
  used for Redis failures and makes unlimited fail-open an explicit rollback choice.
  Source and tests: `RedisRateLimiter.java`, `RedisRateLimiterTest`, and
  `RedisRateLimiterSlidingWindowIntegrationTest`.
- `docs/system_design/15_Eventual_Consistency.md` now owns the Standard and TCC saga
  models plus the two Step Functions definition renderers. Source and tests:
  `SagaOrchestrators.java`, `AwsStepFunctionsSagaDefinition.java`,
  `AwsTccStepFunctionsSagaDefinition.java`, `SagaOrchestratorTest`,
  `TccSagaOrchestratorTest`, `AwsStepFunctionsSagaDefinitionTest`, and
  `AwsTccStepFunctionsSagaDefinitionTest`.

## Coverage summary

All 63 README H2/H3 headings are classified exactly once: 7 `KEEP`, 2 `MERGE`,
38 `LINK`, and 16 `REMOVE`.
