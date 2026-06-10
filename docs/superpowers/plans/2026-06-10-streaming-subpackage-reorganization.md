# Streaming Sub-package Reorganization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the flat `com.recsys.streaming` package (32 main files, 23 test files) into six cohesive sub-packages — `event`, `store`, `serving`, `learner`, `redis`, `ops` — so each group of classes has a name that reflects its responsibility rather than its implementation transport.

**Why not rename to `event/`:** Only 4 of the 32 files are event-related. The dominant concern is *online serving*, so `streaming` stays as the top-level name; sub-packages supply the missing precision.

**Tech Stack:** Java 21, Maven, macOS (`sed -i ''` syntax required)

---

## File map

### `streaming/event/` — publish/subscribe and feedback collection
| File |
|---|
| `AsyncEventPublisher.java` |
| `EventSemantics.java` |
| `ExperienceCollector.java` |
| `LogCollector.java` |

Tests: `AsyncEventPublisherTest`, `ExperienceCollectorTest`, `LogCollectorTest`

### `streaming/store/` — Redis-backed feature storage
| File |
|---|
| `OnlineFeatureStore.java` |
| `RecentHistoryStore.java` |
| `TrendingStore.java` |
| `ShardedRecordService.java` |

Tests: `OnlineFeatureStoreTest`, `ShardedRecordServiceIntegrationTest`

### `streaming/serving/` — HTTP server, prediction, recommendation
| File |
|---|
| `ApiService.java` |
| `OnlineFeaturesService.java` |
| `OnlineLiveService.java` |
| `OnlinePredictionServer.java` |
| `OnlinePredictionService.java` |
| `OnlineRecommendationEngine.java` |
| `OnlineRecommendationRequest.java` |
| `OnlineRecommendationResult.java` |
| `OnlineRecommendationService.java` |

Tests: `OnlinePredictionLoadTest`, `OnlinePredictionServerIntegrationTest`, `OnlineRecommendationEngineTest`, `OnlineRecommendationServiceTest`

### `streaming/learner/` — online learning from streaming feedback
| File |
|---|
| `LearnerFlushScheduler.java` |
| `OnlineJoiner.java` |
| `OnlineLearner.java` |

Tests: `LearnerFlushSchedulerTest`, `OnlineJoinerTest`, `OnlineLearnerTest`

### `streaming/redis/` — Redis infrastructure primitives
| File |
|---|
| `RedisDistributedLock.java` |
| `RedisMutex.java` |
| `RedisRateLimiter.java` |
| `WatchdogLock.java` |

Tests: `RedisDistributedLockTest`, `RedisMutexTest`, `RedisRateLimiterTest`, `WatchdogLockTest`

### `streaming/ops/` — resilience, health, capacity, observability
| File |
|---|
| `FaultInjector.java` |
| `OnlineAdmissionControl.java` |
| `OnlineCapacityService.java` |
| `OnlineHealthService.java` |
| `OnlineLoadShedder.java` |
| `OnlineOpsService.java` |
| `OnlineServingMetricsService.java` |
| `WorkerBulkhead.java` |

Tests: `FaultInjectorTest`, `OnlineAdmissionControlTest`, `OnlineCapacityServiceTest`, `OnlineHealthServiceTest`, `OnlineLoadShedderTest`, `OnlineServingMetricsServiceTest`, `WorkerBulkheadTest`

### External consumers (imports to update in non-streaming packages)
| File | Old import | New import |
|---|---|---|
| `infrastructure/redis/RedisTopKStore.java` | `streaming.TrendingStore` | `streaming.store.TrendingStore` |
| `infrastructure/redis/ShardedTopKStore.java` | `streaming.TrendingStore` | `streaming.store.TrendingStore` |
| `service/retrieval/MultiChannelRecallService.java` | `streaming.FaultInjector` | `streaming.ops.FaultInjector` |
| `service/retrieval/TrendingChannel.java` | `streaming.TrendingStore` | `streaming.store.TrendingStore` |
| `serving/RecSysServer.java` | `streaming.TrendingStore` | `streaming.store.TrendingStore` |
| `service/retrieval/MultiChannelRecallServiceTest.java` | `streaming.FaultInjector`, `streaming.WorkerBulkhead` | `streaming.ops.*` |
| `service/retrieval/TrendingChannelTest.java` | `streaming.TrendingStore` | `streaming.store.TrendingStore` |
| `service/retrieval/WorkerIsolationFailureTest.java` | `streaming.FaultInjector`, `streaming.WorkerBulkhead` | `streaming.ops.*` |

---

## Task 1: Establish baseline

- [ ] **Step 1: Run the full test suite and record the result**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
mvn test -DexcludedGroups=load 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

Expected: BUILD FAILURE with exactly the Docker-related errors (SequenceGeneratorTest, ShardedRecordStore*, ShardedRecordServiceIntegrationTest). Note the exact pass/fail counts — any new failures after the reorganization are regressions.

---

## Task 2: Move files to sub-directories

Create the six sub-directories and move all main + test files using `git mv` so git tracks renames.

- [ ] **Step 1: Create sub-directories**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
BASE=src/main/java/com/recsys/streaming
TEST=src/test/java/com/recsys/streaming
mkdir -p $BASE/event $BASE/store $BASE/serving $BASE/learner $BASE/redis $BASE/ops
mkdir -p $TEST/event $TEST/store $TEST/serving $TEST/learner $TEST/redis $TEST/ops
```

- [ ] **Step 2: Move event files**

```bash
BASE=src/main/java/com/recsys/streaming
TEST=src/test/java/com/recsys/streaming
for f in AsyncEventPublisher EventSemantics ExperienceCollector LogCollector; do
  git mv $BASE/${f}.java $BASE/event/${f}.java
done
for f in AsyncEventPublisherTest ExperienceCollectorTest LogCollectorTest; do
  [ -f $TEST/${f}.java ] && git mv $TEST/${f}.java $TEST/event/${f}.java
done
```

- [ ] **Step 3: Move store files**

```bash
BASE=src/main/java/com/recsys/streaming
TEST=src/test/java/com/recsys/streaming
for f in OnlineFeatureStore RecentHistoryStore TrendingStore ShardedRecordService; do
  git mv $BASE/${f}.java $BASE/store/${f}.java
done
for f in OnlineFeatureStoreTest ShardedRecordServiceIntegrationTest; do
  [ -f $TEST/${f}.java ] && git mv $TEST/${f}.java $TEST/store/${f}.java
done
```

- [ ] **Step 4: Move serving files**

```bash
BASE=src/main/java/com/recsys/streaming
TEST=src/test/java/com/recsys/streaming
for f in ApiService OnlineFeaturesService OnlineLiveService OnlinePredictionServer OnlinePredictionService OnlineRecommendationEngine OnlineRecommendationRequest OnlineRecommendationResult OnlineRecommendationService; do
  git mv $BASE/${f}.java $BASE/serving/${f}.java
done
for f in OnlinePredictionLoadTest OnlinePredictionServerIntegrationTest OnlineRecommendationEngineTest OnlineRecommendationServiceTest; do
  [ -f $TEST/${f}.java ] && git mv $TEST/${f}.java $TEST/serving/${f}.java
done
```

- [ ] **Step 5: Move learner files**

```bash
BASE=src/main/java/com/recsys/streaming
TEST=src/test/java/com/recsys/streaming
for f in LearnerFlushScheduler OnlineJoiner OnlineLearner; do
  git mv $BASE/${f}.java $BASE/learner/${f}.java
done
for f in LearnerFlushSchedulerTest OnlineJoinerTest OnlineLearnerTest; do
  [ -f $TEST/${f}.java ] && git mv $TEST/${f}.java $TEST/learner/${f}.java
done
```

- [ ] **Step 6: Move redis files**

```bash
BASE=src/main/java/com/recsys/streaming
TEST=src/test/java/com/recsys/streaming
for f in RedisDistributedLock RedisMutex RedisRateLimiter WatchdogLock; do
  git mv $BASE/${f}.java $BASE/redis/${f}.java
done
for f in RedisDistributedLockTest RedisMutexTest RedisRateLimiterTest WatchdogLockTest; do
  [ -f $TEST/${f}.java ] && git mv $TEST/${f}.java $TEST/redis/${f}.java
done
```

- [ ] **Step 7: Move ops files**

```bash
BASE=src/main/java/com/recsys/streaming
TEST=src/test/java/com/recsys/streaming
for f in FaultInjector OnlineAdmissionControl OnlineCapacityService OnlineHealthService OnlineLoadShedder OnlineOpsService OnlineServingMetricsService WorkerBulkhead; do
  git mv $BASE/${f}.java $BASE/ops/${f}.java
done
for f in FaultInjectorTest OnlineAdmissionControlTest OnlineCapacityServiceTest OnlineHealthServiceTest OnlineLoadShedderTest OnlineServingMetricsServiceTest WorkerBulkheadTest; do
  [ -f $TEST/${f}.java ] && git mv $TEST/${f}.java $TEST/ops/${f}.java
done
```

- [ ] **Step 8: Verify — no Java files remain at the streaming root (except flink/)**

```bash
find src/main/java/com/recsys/streaming -maxdepth 1 -name "*.java"
# Expected: no output
find src/test/java/com/recsys/streaming -maxdepth 1 -name "*.java"
# Expected: no output
```

---

## Task 3: Update package declarations

Each sub-directory now has files with stale `package com.recsys.streaming;` declarations. Update them to their new sub-package names.

- [ ] **Step 1: event/**

```bash
find src/main/java/com/recsys/streaming/event src/test/java/com/recsys/streaming/event \
  -name "*.java" -exec sed -i '' \
  's/^package com\.recsys\.streaming;/package com.recsys.streaming.event;/' {} \;
```

- [ ] **Step 2: store/**

```bash
find src/main/java/com/recsys/streaming/store src/test/java/com/recsys/streaming/store \
  -name "*.java" -exec sed -i '' \
  's/^package com\.recsys\.streaming;/package com.recsys.streaming.store;/' {} \;
```

- [ ] **Step 3: serving/**

```bash
find src/main/java/com/recsys/streaming/serving src/test/java/com/recsys/streaming/serving \
  -name "*.java" -exec sed -i '' \
  's/^package com\.recsys\.streaming;/package com.recsys.streaming.serving;/' {} \;
```

- [ ] **Step 4: learner/**

```bash
find src/main/java/com/recsys/streaming/learner src/test/java/com/recsys/streaming/learner \
  -name "*.java" -exec sed -i '' \
  's/^package com\.recsys\.streaming;/package com.recsys.streaming.learner;/' {} \;
```

- [ ] **Step 5: redis/**

```bash
find src/main/java/com/recsys/streaming/redis src/test/java/com/recsys/streaming/redis \
  -name "*.java" -exec sed -i '' \
  's/^package com\.recsys\.streaming;/package com.recsys.streaming.redis;/' {} \;
```

- [ ] **Step 6: ops/**

```bash
find src/main/java/com/recsys/streaming/ops src/test/java/com/recsys/streaming/ops \
  -name "*.java" -exec sed -i '' \
  's/^package com\.recsys\.streaming;/package com.recsys.streaming.ops;/' {} \;
```

- [ ] **Step 7: Verify — no file still declares the root package**

```bash
grep -rn "^package com\.recsys\.streaming;" src/main/java/com/recsys/streaming/ \
                                              src/test/java/com/recsys/streaming/
# Expected: no output
```

---

## Task 4: Update import statements

Replace every `import com.recsys.streaming.<ClassName>` with the qualified sub-package form. Run across the entire `src/` tree so both intra-streaming and external consumers are fixed in one pass.

- [ ] **Step 1: event/ classes**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
for cls in AsyncEventPublisher EventSemantics ExperienceCollector LogCollector; do
  find src -name "*.java" -exec sed -i '' \
    "s/import com\.recsys\.streaming\.${cls};/import com.recsys.streaming.event.${cls};/g" {} \;
done
```

- [ ] **Step 2: store/ classes**

```bash
for cls in OnlineFeatureStore RecentHistoryStore TrendingStore ShardedRecordService; do
  find src -name "*.java" -exec sed -i '' \
    "s/import com\.recsys\.streaming\.${cls};/import com.recsys.streaming.store.${cls};/g" {} \;
done
```

- [ ] **Step 3: serving/ classes**

```bash
for cls in ApiService OnlineFeaturesService OnlineLiveService OnlinePredictionServer OnlinePredictionService OnlineRecommendationEngine OnlineRecommendationRequest OnlineRecommendationResult OnlineRecommendationService; do
  find src -name "*.java" -exec sed -i '' \
    "s/import com\.recsys\.streaming\.${cls};/import com.recsys.streaming.serving.${cls};/g" {} \;
done
```

- [ ] **Step 4: learner/ classes**

```bash
for cls in LearnerFlushScheduler OnlineJoiner OnlineLearner; do
  find src -name "*.java" -exec sed -i '' \
    "s/import com\.recsys\.streaming\.${cls};/import com.recsys.streaming.learner.${cls};/g" {} \;
done
```

- [ ] **Step 5: redis/ classes**

```bash
for cls in RedisDistributedLock RedisMutex RedisRateLimiter WatchdogLock; do
  find src -name "*.java" -exec sed -i '' \
    "s/import com\.recsys\.streaming\.${cls};/import com.recsys.streaming.redis.${cls};/g" {} \;
done
```

- [ ] **Step 6: ops/ classes**

```bash
for cls in FaultInjector OnlineAdmissionControl OnlineCapacityService OnlineHealthService OnlineLoadShedder OnlineOpsService OnlineServingMetricsService WorkerBulkhead; do
  find src -name "*.java" -exec sed -i '' \
    "s/import com\.recsys\.streaming\.${cls};/import com.recsys.streaming.ops.${cls};/g" {} \;
done
```

- [ ] **Step 7: Verify — no stale flat streaming imports remain**

```bash
grep -rn "import com\.recsys\.streaming\.[A-Z]" src/ --include="*.java"
# Expected: no output (all imports now reference a sub-package)
```

---

## Task 5: Verify compilation and tests

- [ ] **Step 1: Compile**

```bash
mvn compile -q 2>&1 | tail -10
```

Expected: clean exit (exit code 0). On error, run `mvn compile 2>&1 | grep "error:" | head -20` to find missed imports.

- [ ] **Step 2: Full test suite**

```bash
mvn test -DexcludedGroups=load 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

Expected: same pass/fail counts as Task 1 baseline. Zero new failures.

---

## Task 6: Commit

- [ ] **Step 1: Stage and commit**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
git add src/
git commit -m "$(cat <<'EOF'
refactor: split com.recsys.streaming into event/store/serving/learner/redis/ops sub-packages

The flat streaming package mixed event collection, feature stores, online serving,
learner scheduling, Redis infrastructure, and resilience primitives. Sub-packages
make each group's responsibility explicit without renaming any classes.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Final verification

- [ ] **Step 1: Confirm final directory structure**

```bash
ls src/main/java/com/recsys/streaming/
# Expected: event/  flink/  learner/  ops/  redis/  serving/  store/
```

- [ ] **Step 2: Confirm no stale flat imports anywhere**

```bash
grep -rn "import com\.recsys\.streaming\.[A-Z]" src/ --include="*.java"
# Expected: no output
```

- [ ] **Step 3: Confirm CLAUDE.md package map is still accurate**

The `streaming/` row in CLAUDE.md describes the package correctly at the top level — no update needed unless sub-package detail is desired.
