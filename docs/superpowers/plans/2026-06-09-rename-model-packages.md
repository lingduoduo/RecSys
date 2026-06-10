# Rename Model Packages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename `com.recsys.model` → `com.recsys.domain` (shared domain entities) and `com.recsys.modelbased` → `com.recsys.model` (ONNX model-serving Spring Boot app), so the serving app lives at the natural `com.recsys.model` address.

**Architecture:** Two sequential renames. Step 1 frees up the `model` namespace by moving the 11 shared entity classes to `domain`. Step 2 renames the 79-file `modelbased` package tree (main + test) into the now-vacant `model` slot. Both steps use in-place `sed` for bulk content edits and `git mv` for path tracking, so git history shows clean renames. Non-Java artifacts (pom.xml, k8s YAML, shell scripts, CLAUDE.md) are updated in Step 2.

**Tech Stack:** Java 21, Maven, Spring Boot 3, macOS (`sed -i ''` syntax required)

---

## File map

### Step 1 — `com.recsys.model` → `com.recsys.domain`

| Old path | New path | Change |
|---|---|---|
| `src/main/java/com/recsys/model/Movie.java` | `src/main/java/com/recsys/domain/Movie.java` | package decl |
| `src/main/java/com/recsys/model/MovieCandidate.java` | `src/main/java/com/recsys/domain/MovieCandidate.java` | package decl |
| `src/main/java/com/recsys/model/PredictInstance.java` | `src/main/java/com/recsys/domain/PredictInstance.java` | package decl |
| `src/main/java/com/recsys/model/PredictRequest.java` | `src/main/java/com/recsys/domain/PredictRequest.java` | package decl |
| `src/main/java/com/recsys/model/PredictResponse.java` | `src/main/java/com/recsys/domain/PredictResponse.java` | package decl |
| `src/main/java/com/recsys/model/RankedMovie.java` | `src/main/java/com/recsys/domain/RankedMovie.java` | package decl |
| `src/main/java/com/recsys/model/Rating.java` | `src/main/java/com/recsys/domain/Rating.java` | package decl |
| `src/main/java/com/recsys/model/RecommendationQuery.java` | `src/main/java/com/recsys/domain/RecommendationQuery.java` | package decl |
| `src/main/java/com/recsys/model/RecommendationResponse.java` | `src/main/java/com/recsys/domain/RecommendationResponse.java` | package decl |
| `src/main/java/com/recsys/model/RecommendationResult.java` | `src/main/java/com/recsys/domain/RecommendationResult.java` | package decl + import |
| `src/main/java/com/recsys/model/User.java` | `src/main/java/com/recsys/domain/User.java` | package decl |

**Import updates (main):** `infrastructure/DataLoader`, `infrastructure/DataManager`, `infrastructure/PairPredictionService`, `infrastructure/vectordb/CandidateGenerator`, `modelbased/service/CandidateSelectionService`, `modelbased/service/RetrievalService`, `service/hydrator/RecommendationHydrator`, `service/ranking/CandidateRanker`, `service/ranking/ScoreRanker`, `service/recommendation/RecommendationOrchestrator`, `service/retrieval/EmbeddingChannel`, `service/retrieval/GenreHistoryChannel`, `service/retrieval/MultiChannelRecallService`, `service/retrieval/PopularityChannel`, `service/retrieval/RecallChannel`, `service/retrieval/TrendingChannel`, `serving/MovieService`, `serving/PredictionService`, `serving/RecommendationService`, `serving/SimilarMovieService`, `serving/UserService`, `streaming/OnlineFeaturesService`, `streaming/OnlinePredictionService`, `streaming/OnlineRecommendationEngine`, `streaming/OnlineRecommendationResult`, `streaming/OnlineRecommendationService`

**Import updates (test):** `service/recommendation/RecommendationOrchestratorTest`, `service/retrieval/EmbeddingChannelTest`, `service/retrieval/GenreHistoryChannelTest`, `service/retrieval/MultiChannelRecallServiceTest`, `service/retrieval/PopularityChannelTest`, `service/retrieval/TrendingChannelTest`, `service/retrieval/WorkerIsolationFailureTest`, `serving/RecSysServerIntegrationTest`, `streaming/OnlinePredictionLoadTest`, `streaming/OnlinePredictionServerIntegrationTest`, `streaming/OnlineRecommendationEngineTest`, `streaming/OnlineRecommendationServiceTest`

### Step 2 — `com.recsys.modelbased` → `com.recsys.model`

All 53 files under `src/main/java/com/recsys/modelbased/` and all 26 files under `src/test/java/com/recsys/modelbased/` — package declarations and cross-package imports updated by sed, entire directory tree moved by `git mv`.

Non-Java files also updated:
- `pom.xml` — `<mainClass>` element
- `k8s/base/model-serving.yaml` — `MAIN_CLASS` env var
- `scripts/arthas-diagnostics.sh` — example class name comments
- `.claude/CLAUDE.md` — services table entry point column

---

## Task 1: Establish baseline

- [ ] **Step 1: Run the full test suite and record the result**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
mvn test -DexcludedGroups=load 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

Expected: BUILD FAILURE with exactly 7 Docker-related errors (SequenceGeneratorTest, ShardedRecordStore*, ShardedRecordServiceIntegrationTest). All 636 other tests pass. Note the exact error count — any additional failures after the rename are regressions.

---

## Task 2: Rename `com.recsys.model` → `com.recsys.domain`

**Files modified:** 11 source files (package decl) + ~39 consumers (imports)
**Files moved:** `src/main/java/com/recsys/model/` → `src/main/java/com/recsys/domain/`

- [ ] **Step 1: Update package declarations in the 11 entity files**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
find src/main/java/com/recsys/model -maxdepth 1 -name "*.java" \
  -exec sed -i '' 's/^package com\.recsys\.model;/package com.recsys.domain;/' {} \;
```

Verify:
```bash
head -1 src/main/java/com/recsys/model/Movie.java
# Expected: package com.recsys.domain;
head -1 src/main/java/com/recsys/model/User.java
# Expected: package com.recsys.domain;
```

- [ ] **Step 2: Update all import statements across the entire source tree**

```bash
find src -name "*.java" \
  -exec sed -i '' 's/import com\.recsys\.model\./import com.recsys.domain./g' {} \;
```

The pattern `com\.recsys\.model\.` requires a literal dot after `model` so it does NOT match `com.recsys.modelbased.`.

Verify:
```bash
grep -rn "import com\.recsys\.model\." src/ --include="*.java" | head -5
# Expected: no output
grep -rn "import com\.recsys\.domain\." src/ --include="*.java" | wc -l
# Expected: ~40 lines (one per import across all consumers)
```

- [ ] **Step 3: Create the domain directory and move the files via git mv**

```bash
mkdir -p src/main/java/com/recsys/domain
for f in src/main/java/com/recsys/model/*.java; do
  git mv "$f" "src/main/java/com/recsys/domain/$(basename $f)"
done
rmdir src/main/java/com/recsys/model
```

Verify:
```bash
ls src/main/java/com/recsys/domain/
# Expected: 11 .java files (Movie.java, User.java, Rating.java, ...)
ls src/main/java/com/recsys/model/ 2>&1
# Expected: "No such file or directory"
```

- [ ] **Step 4: Verify compilation**

```bash
mvn compile -q 2>&1 | tail -10
```

Expected: clean exit (exit code 0, no error output). If there are compilation errors, grep for the failing class to find a missed import.

- [ ] **Step 5: Run tests**

```bash
mvn test -DexcludedGroups=load 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

Expected: same result as Task 1 baseline (636 pass, 7 Docker errors).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/domain/
git add -u src/
git commit -m "$(cat <<'EOF'
refactor: rename com.recsys.model -> com.recsys.domain (shared entities)

Frees the 'model' package name for the ONNX model-serving service.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Rename `com.recsys.modelbased` → `com.recsys.model`

**Files modified:** 53 main + 26 test Java files (package decls + cross-file imports)
**Files moved:** `src/main/java/com/recsys/modelbased/` → `src/main/java/com/recsys/model/`; `src/test/java/com/recsys/modelbased/` → `src/test/java/com/recsys/model/`
**Non-Java files:** `pom.xml`, `k8s/base/model-serving.yaml`, `scripts/arthas-diagnostics.sh`, `.claude/CLAUDE.md`

- [ ] **Step 1: Update package declarations in all modelbased files**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
find src -path "*/modelbased*" -name "*.java" \
  -exec sed -i '' 's/^package com\.recsys\.modelbased/package com.recsys.model/' {} \;
```

The pattern matches `package com.recsys.modelbased;` (root) and `package com.recsys.modelbased.service;` (sub-packages) alike — the `;` or `.` suffix is left intact.

Verify:
```bash
head -1 src/main/java/com/recsys/modelbased/ModelApplication.java
# Expected: package com.recsys.model;
head -1 src/main/java/com/recsys/modelbased/service/RecommendationService.java
# Expected: package com.recsys.model.service;
head -1 src/main/java/com/recsys/modelbased/controller/RecommendationController.java
# Expected: package com.recsys.model.controller;
```

- [ ] **Step 2: Update all cross-file import statements**

```bash
find src -name "*.java" \
  -exec sed -i '' 's/import com\.recsys\.modelbased\./import com.recsys.model./g' {} \;
```

Verify:
```bash
grep -rn "import com\.recsys\.modelbased\." src/ --include="*.java" | head -5
# Expected: no output
```

- [ ] **Step 3: Move main source directory**

```bash
git mv src/main/java/com/recsys/modelbased src/main/java/com/recsys/model
```

Verify:
```bash
ls src/main/java/com/recsys/model/
# Expected: ModelApplication.java plus subdirs: config/ controller/ converter/ dto/ entity/ exception/ request/ response/ service/ vo/
ls src/main/java/com/recsys/modelbased/ 2>&1
# Expected: "No such file or directory"
```

- [ ] **Step 4: Move test source directory**

```bash
git mv src/test/java/com/recsys/modelbased src/test/java/com/recsys/model
```

Verify:
```bash
ls src/test/java/com/recsys/model/
# Expected: subdirs matching main: controller/ converter/ service/
```

- [ ] **Step 5: Update pom.xml mainClass**

In `pom.xml` line 74, replace:
```xml
<mainClass>com.recsys.modelbased.ModelApplication</mainClass>
```
With:
```xml
<mainClass>com.recsys.model.ModelApplication</mainClass>
```

Verify:
```bash
grep "mainClass" pom.xml
# Expected: <mainClass>com.recsys.model.ModelApplication</mainClass>
```

- [ ] **Step 6: Update k8s manifest**

In `k8s/base/model-serving.yaml`, replace:
```yaml
value: com.recsys.modelbased.model.ModelApplication
```
With:
```yaml
value: com.recsys.model.ModelApplication
```

Verify:
```bash
grep "ModelApplication" k8s/base/model-serving.yaml
# Expected: value: com.recsys.model.ModelApplication
```

- [ ] **Step 7: Update arthas diagnostics script**

In `scripts/arthas-diagnostics.sh`, the example class names referenced `com.recsys.modelbased.model.service.*` (which had an extra `.model.` that was already incorrect — actual package was `com.recsys.modelbased.service.*`). After rename, the correct paths become `com.recsys.model.service.*`:

```bash
sed -i '' \
  's/com\.recsys\.modelbased\.model\.service\./com.recsys.model.service./g' \
  scripts/arthas-diagnostics.sh
```

Verify:
```bash
grep "recsys\." scripts/arthas-diagnostics.sh
# Expected: lines reference com.recsys.model.service.* only
```

- [ ] **Step 8: Update CLAUDE.md services table**

In `.claude/CLAUDE.md`, replace:
```
`com.recsys.modelbased.model.ModelApplication`
```
With:
```
`com.recsys.model.ModelApplication`
```

(The entry is in the Services & Ports table, Model Serving row.)

Verify:
```bash
grep "ModelApplication" .claude/CLAUDE.md
# Expected: `com.recsys.model.ModelApplication`
```

- [ ] **Step 9: Verify compilation**

```bash
mvn compile -q 2>&1 | tail -10
```

Expected: clean exit (exit code 0). If errors appear, run `mvn compile 2>&1 | grep "error:" | head -20` to find the specific broken import.

- [ ] **Step 10: Run full test suite**

```bash
mvn test -DexcludedGroups=load 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

Expected: same result as Task 1 baseline (636 pass, 7 Docker errors).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/recsys/model/
git add src/test/java/com/recsys/model/
git add pom.xml k8s/base/model-serving.yaml scripts/arthas-diagnostics.sh .claude/CLAUDE.md
git add -u src/
git commit -m "$(cat <<'EOF'
refactor: rename com.recsys.modelbased -> com.recsys.model (ONNX serving app)

The 'model' namespace is now the natural home for the model-serving
Spring Boot service. Also fixes stale class references in pom.xml,
k8s manifest, and arthas script.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Final verification

- [ ] **Step 1: Full test suite — confirm baseline is preserved**

```bash
mvn test -DexcludedGroups=load 2>&1 | grep -E "Tests run:|BUILD|FAIL" | tail -10
```

Expected: BUILD FAILURE with exactly 7 Docker errors. Zero additional failures.

- [ ] **Step 2: Confirm no stale `modelbased` references remain in Java source**

```bash
grep -rn "modelbased" src/ --include="*.java"
```

Expected: no output.

- [ ] **Step 3: Confirm no stale `com.recsys.model.` imports for the old domain entities**

```bash
grep -rn "import com\.recsys\.model\." src/ --include="*.java" | head -5
```

Expected: no output (all domain imports now use `com.recsys.domain.*`).

- [ ] **Step 4: Confirm final package directory structure**

```bash
ls src/main/java/com/recsys/
```

Expected:
```
domain/          ← 11 shared entity classes (Movie, User, Rating, ...)
featureflags/
infrastructure/
microservice/
model/           ← Spring Boot ONNX serving app (ModelApplication + subpackages)
mysql/
saga/
service/
serving/
streaming/
```

- [ ] **Step 5: Confirm Spring Boot application entry point**

```bash
grep "mainClass" pom.xml
# Expected: <mainClass>com.recsys.model.ModelApplication</mainClass>

grep "ModelApplication" k8s/base/model-serving.yaml
# Expected: value: com.recsys.model.ModelApplication
```
