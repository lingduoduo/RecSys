# SPEC: Reorganize `com.recsys` into Clean-Architecture Layers

## 1. Objective

Restructure the entire `com.recsys` Java package tree into a set of **global, shared
architectural layers** so the package a class lives in advertises its *role*
(transport, use-case, domain, technical adapter, cross-cutting concern) instead of
the *service* that happens to use it today.

- **Scope:** whole `com.recsys` tree (all four services: serving, model, online, microservice + shared packages).
- **Layer model:** one global set of top-level layers shared across services (not per-service roots).
- **Behavior guarantee:** primarily **moves** (package + import changes) with **light cleanup**
  permitted — rename/merge obviously-misplaced classes, delete dead code/empty packages found en route.
  No functional logic changes; the test suite must pass identically before and after.

**Target users:** the engineers maintaining this backend; the win is faster navigation and clearer
ownership boundaries between transport, application logic, domain types, and infrastructure adapters.

### Non-goals
- No behavior, API contract, wire-format, or Redis-key changes.
- No splitting/merging of the four deployables; only Java package layout changes.
- No rewrite of `streaming/flink` or `training/rulebased` logic (they are excluded from the Maven compile — they move packages only if trivial, otherwise stay put; see Boundaries).

## 2. Commands

```bash
mvn package -DskipTests                       # compile check after each phase
mvn test                                      # full suite — must stay green between phases
mvn test -Dtest=RecommendationServiceTest     # spot-check a single class
```

Per-service smoke (after the move, verify each main class still boots):
```bash
mvn exec:java -Dexec.mainClass=com.recsys.api.serving.RecSysServer
mvn spring-boot:run                           # ModelApplication (new package)
mvn exec:java -Dexec.mainClass=com.recsys.api.online.OnlinePredictionServer
mvn exec:java -Dexec.mainClass=com.recsys.api.gateway.MicroserviceGatewayServer
```

## 3. Target Project Structure

```
com.recsys
├── api                       # transport / entry points
│   ├── serving               # RecSysServer (Jetty) + offline servlet handlers
│   ├── online                # OnlinePredictionServer (Jetty) + online servlet handler
│   ├── gateway               # MicroserviceGatewayServer (Jetty)
│   ├── rest                  # Spring Boot app + @RestController classes
│   ├── request               # inbound request DTOs
│   ├── response              # outbound response DTOs
│   ├── converter             # domain <-> transport mappers
│   └── envelope              # ApiResponse / ApiError / ApiResponseUtil
│
├── application               # use-case orchestration ("what the system does")
│   ├── recommendation
│   ├── retrieval             # recall channels, coldstart, multichannel, user-tower stage
│   ├── ranking
│   ├── feature
│   ├── experiment            # A/B test + exposure logging + variant resolution
│   ├── auth                  # login + submit-token services
│   ├── model                 # ONNX inference pipeline + artifact/version services
│   ├── online                # online recommend/learner orchestration
│   ├── gateway               # proxy / LLM-proxy / route table services
│   ├── knowledge             # knowledge-base facade
│   ├── pagination            # cursor pagination services
│   └── saga                  # saga orchestrators
│
├── domain                    # domain models / value types ("the nouns")
│   ├── user
│   ├── item
│   ├── rating
│   ├── recommendation
│   ├── prediction
│   ├── online
│   ├── knowledge
│   └── saga
│
├── infrastructure            # technical adapters ("how we reach the outside")
│   ├── redis                 # connection factory, replica router, stores + sharding
│   ├── cache                 # embedding caches + LLM response cache
│   ├── vectordb              # candidate generation, LSH, vector index/math
│   ├── store                 # online feature / recent-history / trending stores
│   ├── messaging             # async/Kafka event publishers + collectors
│   ├── persistence           # MySQL client
│   ├── lock                  # Redis distributed locks / mutex / watchdog
│   ├── featureflags          # flag providers
│   ├── dataloading           # DataLoader / DataManager
│   ├── resilience            # BloomFilterGuard / HotKeyDetector / SingleFlight
│   ├── alb                   # AWS ALB model
│   ├── autoscaling           # AWS autoscaling model
│   └── streaming             # flink job (EXCLUDED from compile — see Boundaries)
│
├── observability             # metrics, tracing, runtime monitors
│
├── reliability               # load shedding, circuit breaking, rate limiting, bulkheads, admission control, graceful shutdown
│
├── config                    # Spring config, properties, env config, web/auth wiring
│
└── exception                 # exception types + global handler
```

## 4. Migration Mapping (current → target)

Grouped by **target** layer. Source is the current path under `com.recsys/`.

### api
| Current | Target |
|---|---|
| `serving/RecSysServer`, `serving/BaseApiService`, `serving/CatalogService`, `serving/EmbeddingService`, `serving/PredictionService`, `serving/RecommendationService` | `api/serving` |
| `online/serving/OnlinePredictionServer`, `online/serving/ApiService` | `api/online` |
| `microservice/MicroserviceGatewayServer` | `api/gateway` |
| `model/ModelApplication` | `api/rest` |
| `model/controller/*` (Health, Login, Recommendation, RecommendationV2, Version), `model/knowledge/KnowledgeBaseController` | `api/rest` |
| `model/request/*` | `api/request` |
| `model/response/*` | `api/response` |
| `model/converter/RecommendationConverter`, `model/knowledge/KnowledgeBaseConverter` | `api/converter` |
| `model/dto/ApiResponse`, `ApiError`, `ApiResponseUtil` | `api/envelope` |

### application
| Current | Target |
|---|---|
| `model/service/RecommendationService`, `service/recommendation/*` (Orchestrator, Pipeline, SequentialRecommendationPipeline), `model/service/CandidateSelectionService`, `model/service/RecommendationCache`, `service/hydrator/RecommendationHydrator` | `application/recommendation` |
| `service/retrieval/*` (RecallChannel, RecallScoring, channels, coldstart, multichannel), `model/service/ModelRetrievalStage`, `model/service/UserTowerInferenceService` | `application/retrieval` |
| `service/ranking/*` (CandidateRanker, ScoreRanker), `model/service/RankingStage` | `application/ranking` |
| `model/service/FeatureEncoder` | `application/feature` |
| `model/service/ABTestService`, `AbExposureLogger`, `StableBucketer`, `VariantRuntimeResolver`, `ModelVariants` | `application/experiment` |
| `model/service/LoginTokenService`, `SubmitTokenService` | `application/auth` |
| `model/service/OnnxInferencePipeline`, `ModelRuntime`, `ModelRuntimeProvider`, `ModelArtifactService`, `ModelArtifactLocator`, `ModelVersionService`, `ScoredItems`, `VocabMembershipEmbeddingStore`, `infrastructure/PairPredictionService` | `application/model` |
| `online/serving/OnlineRecommendationService`, `OnlineRecommendV2Service`, `OnlineLiveService`, `OnlineBlendingPipeline`, `OnlineServices`, `online/learner/*` (OnlineLearner, OnlineJoiner, LearnerFlushScheduler) | `application/online` |
| `microservice/GatewayProxyService`, `LlmProxyService`, `GatewayAuthenticator`, `GatewayHealthService`, `MicroserviceRoute`, `MicroserviceRouteTable` | `application/gateway` |
| `model/knowledge/KnowledgeBaseFacadeService` | `application/knowledge` |
| `service/pagination/*` (CursorPaginationService, MillionScalePaginationSql, RankedListCursor, Page) | `application/pagination` |
| `saga/SagaOrchestrators`, `SagaDefinition`, `AwsStepFunctionsSagaDefinition`, `AwsTccStepFunctionsSagaDefinition`, `InMemorySagaStateStore`, `SagaStateStore`, `SagaEventPublisher` | `application/saga` |

### domain
| Current | Target |
|---|---|
| `domain/User` | `domain/user` |
| `domain/Movie`, `MovieCandidate`, `RankedMovie` | `domain/item` |
| `domain/Rating` | `domain/rating` |
| `domain/RecommendationQuery`, `RecommendationResponse`, `RecommendationResult` | `domain/recommendation` |
| `domain/PredictInstance`, `PredictRequest`, `PredictResponse`, `model/dto/ScoredItem` | `domain/prediction` |
| `online/serving/OnlineRecommendationRequest`, `OnlineRecommendationResult`, `online/event/EventSemantics` | `domain/online` |
| `model/knowledge/KnowledgeBase`, `KnowledgeBaseDTO`, `KnowledgeBaseVO`, `CreateKnowledgeBaseRequest/Response`, `GetKnowledgeBasesResponse`, `UpdateKnowledgeBaseRequest` | `domain/knowledge` |
| `saga/SagaInstance`, `SagaStatus`, `SagaStep`, `SagaStepAction`, `SagaEventType`, `SagaTransitionEvent`, `TccParticipant`, `SagaBackoff` | `domain/saga` |

### infrastructure
| Current | Target |
|---|---|
| `infrastructure/redis/*` (+ `sharding/*`) | `infrastructure/redis` (keep `sharding` sub-pkg) |
| `infrastructure/cache/*`, `microservice/LlmResponseCache` | `infrastructure/cache` |
| `infrastructure/vectordb/*` | `infrastructure/vectordb` |
| `online/store/*` (OnlineFeatureStore, RecentHistoryStore, TrendingStore, ShardedRecordService) | `infrastructure/store` |
| `online/event/AsyncEventPublisher`, `KafkaAsyncEventPublisher`, `ExperienceCollector`, `LogCollector` | `infrastructure/messaging` |
| `mysql/*` (MySqlClient, MySqlConnectionSettings) | `infrastructure/persistence` |
| `online/redis/RedisDistributedLock`, `RedisMutex`, `WatchdogLock` | `infrastructure/lock` |
| `featureflags/*` (provider, service, flags, models, providers) | `infrastructure/featureflags` |
| `infrastructure/DataLoader`, `DataManager` | `infrastructure/dataloading` |
| `infrastructure/BloomFilterGuard`, `HotKeyDetector`, `SingleFlight` | `infrastructure/resilience` |
| `infrastructure/alb/*`, `infrastructure/autoscaling/*` | unchanged |
| `online/flink/*` (OnlineFeatureStreamingJob, MovieEvent), `training/rulebased/*` | `infrastructure/streaming` (**only if it stays out of compile cleanly**; otherwise leave in place — see Boundaries) |

### observability
| Current | Target |
|---|---|
| `model/service/InferenceMetricsService`, `GcEventTracker`, `JvmMemoryMonitor` | `observability` |
| `online/ops/OnlineServingMetricsService` | `observability` |
| `config/TraceIdAspect` | `observability` |

### reliability
| Current | Target |
|---|---|
| `model/service/LoadShedder`, `GracefulShutdownSupport`, `ModelRateLimiter` | `reliability` |
| `online/ops/OnlineAdmissionControl`, `OnlineLoadShedder`, `WorkerBulkhead`, `FaultInjector`, `OnlineCapacityService`, `OnlineHealthService`, `OnlineOpsService` | `reliability` |
| `microservice/RouteCircuitBreaker`, `GatewayRateLimiter`, `TokenBucket`, `LlmTokenRateLimiter` | `reliability` |
| `online/redis/RedisRateLimiter` | `reliability` |

### config
| Current | Target |
|---|---|
| `config/*` (all existing Spring config + properties) | `config` (unchanged) |
| `infrastructure/EnvConfig`, `microservice/EnvVars` | `config` |
| `model/config/ModelEventConfig`, `featureflags/config/FeatureFlagConfig` | `config` |
| `annotation/NeedLogin` | `config` |

### exception
| Current | Target |
|---|---|
| `model/exception/*` (PipelineNotImplemented, RateLimitExceeded, ServiceOverloaded, SubmitToken, Unauthorized) | `exception` |
| `config/GlobalExceptionHandler` | `exception` |
| `saga/SagaException`, `SagaConflictException` | stay in `domain/saga` (cohesion with saga types) |

## 5. Classification Rules (for edge cases & implementer judgment)

1. **Touches HTTP/servlet/transport?** → `api`.
2. **Orchestrates a use case (coordinates domain + infra, has business "verbs")?** → `application`.
3. **Plain data/value type with no framework deps?** → `domain`.
4. **Wraps an external system (Redis, MySQL, Kafka, ONNX runtime, AWS, vector index)?** → `infrastructure`.
5. **Metrics / tracing / runtime monitoring only?** → `observability`.
6. **Protects the system under load/failure (shed, break, throttle, bulkhead, shutdown)?** → `reliability`.
7. **Spring `@Configuration`/`@ConfigurationProperties`/env reader/aspect wiring?** → `config`.
8. **Throwable type or global handler?** → `exception` (except domain-cohesive saga exceptions).

When a class spans two layers (e.g. a "Service" that is really a servlet handler), classify by its
**primary collaborator**: HTTP request/response → `api`; downstream stores → `application`.

## 6. External References to Update (in lockstep with moves)

These break the build/runtime if not updated alongside the package moves:

- `pom.xml` → `<mainClass>com.recsys.api.rest.ModelApplication</mainClass>`.
- `ModelApplication` → `@SpringBootApplication(scanBasePackages = {"com.recsys"})` (broadened; verify no stray beans pulled in — narrow to an explicit layer list if it does).
- `scripts/run-microservices-local.sh` → three updated `mainClass` FQNs.
- `scripts/run-with-jvm-tuning.sh` → two updated `mainClass` FQNs.
- `k8s/base/online-serving.yaml`, `api-gateway.yaml`, `model-serving.yaml`, `catalog-serving.yaml` → updated `MAIN_CLASS` env values.
- `.claude/CLAUDE.md` → Services table entry points, Package Map, Redis/streaming notes.
- Test sources under `src/test/java/com/recsys/**` (138 files) move to mirror the new main packages.

## 7. Code Style

- Match existing conventions: one top-level class per file, package-private helpers where already used.
- Update `package` declarations and `import` statements only; do not reformat untouched lines.
- Preserve class names except where a rename resolves a genuine ambiguity (see collisions below).
- Keep `sharding` as a sub-package of `infrastructure/redis`.

### Naming collisions to watch (FQN differs, so usually no rename needed)
- `RecommendationService` ×3 → `api/serving`, `application/recommendation` (model), `application/online` (`OnlineRecommendationService`). Distinct packages — keep names.
- `Page` ×2 → `infrastructure/redis/sharding/Page`, `application/pagination/Page`. Keep.
- `ApiService` ×2 → `api/serving/BaseApiService`, `api/online/ApiService`. Keep.

## 8. Testing Strategy

- **Green-between-phases:** `mvn test` must pass after every phase; never leave the tree uncompilable across a commit.
- Move each test class in the same phase as its subject; update its `package`/imports.
- Load tests stay opt-in (`-Dgroups=load`), unchanged.
- After all phases: run `mvn package -DskipTests` + full `mvn test`, then boot all four main classes (smoke commands in §2) to confirm wiring and Spring component scan still resolve.
- Excluded modules (`infrastructure/streaming`, rulebased) are not compiled by Maven — confirm they remain excluded and do not break IDE-only builds.

## 9. Phased Execution Plan

Each phase = compile + test green + one commit on a feature branch (never commit to `main`).
Order chosen so leaf layers (no inbound deps) move first, reducing churn.

1. **domain** — value types move first; everything depends on them.
2. **exception** — small, low-risk.
3. **infrastructure** (redis, cache, vectordb, store, messaging, persistence, lock, featureflags, dataloading, resilience, alb, autoscaling).
4. **observability** + **reliability**.
5. **config** (incl. EnvConfig/EnvVars consolidation, NeedLogin).
6. **application** (recommendation, retrieval, ranking, feature, experiment, auth, model, online, gateway, knowledge, pagination, saga).
7. **api** (serving, online, gateway, rest, request, response, converter, envelope) + all external refs in §6.
8. **Cleanup** — delete now-empty packages (`annotation`, `data`, old `service/`, `mysql/`, etc.), remove dead code surfaced during the move, final full-suite + smoke run.

## 10. Boundaries

**Always:**
- Keep `mvn test` green between phases; commit per phase on a feature branch.
- Update every external reference in §6 in the same phase as the corresponding move.
- Use `git mv`-style moves so history is preserved; change only `package`/`import` lines in otherwise-untouched files.
- Open a PR for review at the end (never merge to `main` directly).

**Ask first:**
- Broadening `scanBasePackages` to `com.recsys` if it pulls in unexpected beans — confirm the explicit-list fallback.
- Any rename that changes a public class name (vs. just its package).
- Moving `streaming/flink` / `training/rulebased` into `infrastructure/streaming` if it risks the Maven compile-exclude config — default is to leave them in place and only document.
- Deleting any file that looks dead but has no test coverage proving it unused.

**Never:**
- Change runtime behavior, HTTP contracts, wire formats, or Redis key conventions.
- Merge the four deployables or alter their boundaries.
- Reformat or "improve" code unrelated to the move within the same change.
- Commit a state where the project does not compile.

## 11. Open Risks / Notes

- `@SpringBootApplication` component scan currently lists `{"com.recsys.model","com.recsys.config"}`; the move spreads `@Service`/`@Component` beans across new packages, so the scan must broaden — primary correctness risk, covered by the §2 smoke boot.
- The offline (`serving`), online, and gateway servers are plain Jetty (no Spring scan), so their beans are wired manually — moving them is import-only but their `main` FQNs are referenced externally (§6).
- `domain/knowledge` request/response types could alternatively live under `api/request`+`api/response`; spec places them in `domain/knowledge` for cohesion. Flag if reviewers prefer the api split.
- Empty/near-empty legacy packages (`annotation`, `data`) are removed in Phase 8.
