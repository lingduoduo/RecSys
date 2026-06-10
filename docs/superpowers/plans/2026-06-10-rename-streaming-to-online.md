# Rename streaming → online Package Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename `com.recsys.streaming` → `com.recsys.online` so the package name reflects what the code *is* (an online serving layer) rather than how its data arrives. Every class is named `Online*`; the `streaming` label belongs only to the `flink/` sub-directory.

**Scope:** All sub-packages (`event`, `store`, `serving`, `learner`, `redis`, `ops`, `flink`) move together. Four non-Java files are also updated.

**Tech Stack:** Java 21, Maven, macOS (`sed -i ''` syntax required)

---

## File map

### Java source
| Old path root | New path root |
|---|---|
| `src/main/java/com/recsys/streaming/` | `src/main/java/com/recsys/online/` |
| `src/test/java/com/recsys/streaming/` | `src/test/java/com/recsys/online/` |

Package declarations and import statements: every occurrence of `com.recsys.streaming.` becomes `com.recsys.online.` (single sed pass covers both because all classes are now in sub-packages — no root-level `com.recsys.streaming;` declarations exist after the sub-package split).

### Non-Java files
| File | Change |
|---|---|
| `.claude/CLAUDE.md` | Services table entry point + package map row |
| `pom.xml` | `<mainClass>` element |
| `k8s/base/online-serving.yaml` | `MAIN_CLASS` env var |
| `scripts/run-microservices-local.sh` | `exec.mainClass` argument |

### External consumers (outside streaming/)
| File | Old import | New import |
|---|---|---|
| `infrastructure/redis/RedisTopKStore.java` | `streaming.store.TrendingStore` | `online.store.TrendingStore` |
| `infrastructure/redis/ShardedTopKStore.java` | `streaming.store.TrendingStore` | `online.store.TrendingStore` |
| `service/retrieval/MultiChannelRecallService.java` | `streaming.ops.FaultInjector` | `online.ops.FaultInjector` |
| `service/retrieval/TrendingChannel.java` | `streaming.store.TrendingStore` | `online.store.TrendingStore` |
| `serving/RecSysServer.java` | `streaming.store.TrendingStore` | `online.store.TrendingStore` |
| Test counterparts of the above | same pattern | same pattern |

(All covered by the single sed pass over `src/`.)

---

## Task 1: Establish baseline

- [ ] **Step 1: Run the full test suite and record the result**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
mvn test -DexcludedGroups=load 2>&1 | grep -E "Tests run:|BUILD" | tail -3
```

Expected: `Tests run: 643, Failures: 0, Errors: 7` + `BUILD FAILURE` (7 Docker errors only).

---

## Task 2: Update all Java content (package declarations + imports)

A single sed pattern covers every occurrence — `com.recsys.streaming.` always has a dot after `streaming` now that all classes live in sub-packages.

- [ ] **Step 1: Replace `com.recsys.streaming.` → `com.recsys.online.` in all Java files**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
find src -name "*.java" \
  -exec sed -i '' 's/com\.recsys\.streaming\./com.recsys.online./g' {} \;
```

- [ ] **Step 2: Verify — no stale `streaming` package references remain in Java source**

```bash
grep -rn "com\.recsys\.streaming" src/ --include="*.java"
# Expected: no output
```

- [ ] **Step 3: Spot-check a package declaration and an import**

```bash
head -1 src/main/java/com/recsys/streaming/serving/OnlinePredictionServer.java
# Expected: package com.recsys.online.serving;

grep "import com.recsys.online" src/main/java/com/recsys/infrastructure/redis/ShardedTopKStore.java
# Expected: import com.recsys.online.store.TrendingStore;
```

---

## Task 3: Move source directories

- [ ] **Step 1: Move main source tree**

```bash
git mv src/main/java/com/recsys/streaming src/main/java/com/recsys/online
```

Verify:
```bash
ls src/main/java/com/recsys/online/
# Expected: event/  flink/  learner/  ops/  redis/  serving/  store/
ls src/main/java/com/recsys/streaming/ 2>&1
# Expected: "No such file or directory"
```

- [ ] **Step 2: Move test source tree**

```bash
git mv src/test/java/com/recsys/streaming src/test/java/com/recsys/online
```

Verify:
```bash
ls src/test/java/com/recsys/online/
# Expected: event/  learner/  ops/  redis/  serving/  store/
ls src/test/java/com/recsys/streaming/ 2>&1
# Expected: "No such file or directory"
```

---

## Task 4: Update non-Java files

- [ ] **Step 1: CLAUDE.md — entry point and package map**

Replace both `com.recsys.streaming.OnlinePredictionServer` occurrences and the package map row:

```bash
sed -i '' \
  's/com\.recsys\.streaming\.OnlinePredictionServer/com.recsys.online.serving.OnlinePredictionServer/g' \
  .claude/CLAUDE.md
sed -i '' \
  's/| `streaming\/` |/| `online\/` |/' \
  .claude/CLAUDE.md
```

Verify:
```bash
grep "streaming" .claude/CLAUDE.md | grep -v "docker-compose\|Kafka\|Flink\|streaming feedback"
# Expected: no output (only docker-compose.streaming.yml and prose refs remain)
```

- [ ] **Step 2: pom.xml**

```bash
sed -i '' \
  's/com\.recsys\.streaming\.OnlinePredictionServer/com.recsys.online.serving.OnlinePredictionServer/g' \
  pom.xml
```

Verify:
```bash
grep "OnlinePredictionServer" pom.xml
# Expected: <mainClass>com.recsys.online.serving.OnlinePredictionServer</mainClass>
```

- [ ] **Step 3: k8s/base/online-serving.yaml**

```bash
sed -i '' \
  's/com\.recsys\.streaming\.OnlinePredictionServer/com.recsys.online.serving.OnlinePredictionServer/g' \
  k8s/base/online-serving.yaml
```

Verify:
```bash
grep "OnlinePredictionServer" k8s/base/online-serving.yaml
# Expected: value: com.recsys.online.serving.OnlinePredictionServer
```

- [ ] **Step 4: scripts/run-microservices-local.sh**

```bash
sed -i '' \
  's/com\.recsys\.streaming\.OnlinePredictionServer/com.recsys.online.serving.OnlinePredictionServer/g' \
  scripts/run-microservices-local.sh
```

Verify:
```bash
grep "OnlinePredictionServer" scripts/run-microservices-local.sh
# Expected: ...mainClass=com.recsys.online.serving.OnlinePredictionServer
```

---

## Task 5: Verify compilation and tests

- [ ] **Step 1: Compile**

```bash
mvn compile -q 2>&1 | tail -5
```

Expected: clean exit. On error run `mvn compile 2>&1 | grep "error:" | head -20`.

- [ ] **Step 2: Full test suite**

```bash
mvn test -DexcludedGroups=load 2>&1 | grep -E "Tests run:|BUILD" | tail -3
```

Expected: `Tests run: 643, Failures: 0, Errors: 7` — identical to Task 1 baseline.

---

## Task 6: Commit

- [ ] **Step 1: Stage and commit**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
git add src/ .claude/CLAUDE.md pom.xml k8s/base/online-serving.yaml scripts/run-microservices-local.sh
git commit -m "$(cat <<'EOF'
refactor: rename com.recsys.streaming -> com.recsys.online

The package name now reflects what the code is (an online serving layer)
rather than how its data arrives. Every class is named Online*; 'streaming'
belongs only to the flink/ sub-directory for stream-processing jobs.

Also updates CLAUDE.md, pom.xml, k8s manifest, and run script to reference
the correct entry point: com.recsys.online.serving.OnlinePredictionServer.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Final verification

- [ ] **Step 1: Confirm directory structure**

```bash
ls src/main/java/com/recsys/
# Expected: domain/  featureflags/  infrastructure/  microservice/  model/  mysql/  online/  saga/  service/  serving/
```

- [ ] **Step 2: Confirm no stale streaming package references in Java**

```bash
grep -rn "com\.recsys\.streaming" src/ --include="*.java"
# Expected: no output
```

- [ ] **Step 3: Confirm entry point**

```bash
grep "OnlinePredictionServer" pom.xml .claude/CLAUDE.md k8s/base/online-serving.yaml
# Expected: all lines reference com.recsys.online.serving.OnlinePredictionServer
```
