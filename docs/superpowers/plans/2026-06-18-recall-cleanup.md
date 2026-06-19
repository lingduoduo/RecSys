# Recall Cleanup Implementation Plan (Sub-project 3 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire the dead `OnlineRecommendationEngine` and make the recall channel timeout env-tunable (`RECALL_CHANNEL_TIMEOUT_MS`) for both ports via the shared `RecallConfig.Builder`, with no behavior change when the env var is unset.

**Architecture:** `RecallConfig.Builder`'s default `channelTimeoutMs` reads `RECALL_CHANNEL_TIMEOUT_MS` (default 200). Both servers drop their explicit `.channelTimeoutMs(...)` calls and inherit it. `OnlineRecommendationEngine` (zero `src/main` callers after sub-project 2) and its test are deleted.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ.

## Global Constraints

- Java 17, Maven. `mvn test -Dtest=<Class>` runs one class. This branch stacks on `feat/online-recall-adoption` (PR #126), which removed the engine's construction from `OnlinePredictionServer`.
- No behavior change when `RECALL_CHANNEL_TIMEOUT_MS` is unset: both ports keep 200 ms.
- Env var name exactly `RECALL_CHANNEL_TIMEOUT_MS`, default `200L`, read via a package-private `RecallConfig.readLongEnv(String,long)` (parse `System.getenv`; fall back on null/blank/`NumberFormatException`) — same shape as `ShardedTopKStore.readLongEnv`.
- `MultiChannelRecallService`'s own `DEFAULT_CHANNEL_TIMEOUT_MS` (test-convenience constructors) is left unchanged — not on the production path.
- No shared registry/factory; do not change channel sets or quotas.

---

### Task 1: Env-tunable channel timeout in `RecallConfig.Builder`

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/multichannel/RecallConfig.java`
- Test: `src/test/java/com/recsys/service/retrieval/multichannel/RecallConfigTest.java` (extend)

**Interfaces:**
- Produces: package-private `static long RecallConfig.readLongEnv(String name, long defaultValue)`; `Builder` default `channelTimeoutMs` now sourced from `RECALL_CHANNEL_TIMEOUT_MS` (default 200). `RecallConfig.channelTimeoutMs()` accessor (existing record component) unchanged.

- [ ] **Step 1: Write the failing tests**

Append to `RecallConfigTest.java` (inside the class; imports `List`, `Map`, `ForkJoinPool`, `MovieCandidate`, `assertThat` already present):

```java
    @Test
    void defaultChannelTimeoutIs200WhenEnvUnset() {
        RecallConfig config = RecallConfig.builder()
                .channels(List.of(channel("c", new MovieCandidate("1", 1.0, "c", Map.of()))))
                .executor(ForkJoinPool.commonPool())
                .build();
        assertThat(config.channelTimeoutMs()).isEqualTo(200L);
    }

    @Test
    void readLongEnvReturnsSuppliedDefaultWhenVarUnset() {
        // RECALL_CHANNEL_TIMEOUT_MS is not set in the test environment; the helper returns the default.
        assertThat(RecallConfig.readLongEnv("RECALL_CHANNEL_TIMEOUT_MS", 200L)).isEqualTo(200L);
        assertThat(RecallConfig.readLongEnv("RECALL_CHANNEL_TIMEOUT_MS", 1500L)).isEqualTo(1500L);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=RecallConfigTest`
Expected: COMPILATION FAILURE — `RecallConfig.readLongEnv` does not exist.

- [ ] **Step 3: Modify `RecallConfig`**

(a) Change the `Builder` field default (currently `private long channelTimeoutMs = 200L;`):

```java
        private long channelTimeoutMs = readLongEnv("RECALL_CHANNEL_TIMEOUT_MS", 200L);
```

(b) Add the helper as a `static` method on `RecallConfig` (e.g. just after `public static Builder builder() { ... }`):

```java
    static long readLongEnv(String name, long defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=RecallConfigTest`
Expected: PASS (existing 3 + 2 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/multichannel/RecallConfig.java \
        src/test/java/com/recsys/service/retrieval/multichannel/RecallConfigTest.java
git commit -m "feat: make recall channel timeout env-tunable (RECALL_CHANNEL_TIMEOUT_MS)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Drop explicit `.channelTimeoutMs(...)` from both servers

**Files:**
- Modify: `src/main/java/com/recsys/serving/RecSysServer.java`
- Modify: `src/main/java/com/recsys/online/serving/OnlinePredictionServer.java`

**Interfaces:**
- Consumes: `RecallConfig.Builder`'s env-tunable default `channelTimeoutMs` (Task 1).
- Produces: both servers inherit the env-tunable timeout; `RecSysServer.DEFAULT_CHANNEL_TIMEOUT_MS` constant removed.

- [ ] **Step 1: Edit `RecSysServer`**

Remove the line `.channelTimeoutMs(DEFAULT_CHANNEL_TIMEOUT_MS)` from the `RecallConfig.builder()` chain (around line 105).

Then confirm `DEFAULT_CHANNEL_TIMEOUT_MS` has no other references:

Run: `grep -n DEFAULT_CHANNEL_TIMEOUT_MS src/main/java/com/recsys/serving/RecSysServer.java`
Expected: only the declaration line remains (the usage is now gone).

Remove the now-unused constant declaration `private static final long DEFAULT_CHANNEL_TIMEOUT_MS = 200L;` (around line 44).

- [ ] **Step 2: Edit `OnlinePredictionServer`**

Remove the line `.channelTimeoutMs(200L)` from the `RecallConfig.builder()` chain (around line 93).

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (no unused-symbol or missing-symbol errors).

- [ ] **Step 4: Run both ports' regression tests**

Run: `mvn test -Dtest=RecSysServerIntegrationTest,RecSysServerRegressionTest,OnlinePredictionServerIntegrationTest,OnlinePredictionRegressionTest`
Expected: PASS — timeout still defaults to 200 ms via the builder; behavior identical.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/serving/RecSysServer.java \
        src/main/java/com/recsys/online/serving/OnlinePredictionServer.java
git commit -m "refactor: both ports inherit env-tunable recall channel timeout

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Retire `OnlineRecommendationEngine`

**Files:**
- Delete: `src/main/java/com/recsys/online/serving/OnlineRecommendationEngine.java`
- Delete: `src/test/java/com/recsys/online/serving/OnlineRecommendationEngineTest.java`

**Interfaces:**
- Consumes: nothing. Produces: removal of the dead class (no `src/main` callers after sub-project 2).

- [ ] **Step 1: Confirm no remaining references**

Run: `grep -rn "OnlineRecommendationEngine" src/main/java`
Expected: only matches inside `OnlineRecommendationEngine.java` itself (the class/constructor) — no other `src/main` file references it.

Run: `grep -rln "OnlineRecommendationEngine" src/test/java`
Expected: only `OnlineRecommendationEngineTest.java`.

If any OTHER file references it, STOP and report — the deletion is unsafe and the plan needs revisiting.

- [ ] **Step 2: Delete the files**

```bash
git rm src/main/java/com/recsys/online/serving/OnlineRecommendationEngine.java \
       src/test/java/com/recsys/online/serving/OnlineRecommendationEngineTest.java
```

- [ ] **Step 3: Full build + test suite**

Run: `mvn test`
Expected: BUILD SUCCESS — the deletion compiles (proves nothing referenced the engine) and all tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: retire dead OnlineRecommendationEngine (superseded by OnlineRecentHistoryChannel)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Load-test guard

**Files:** none (verification only).

- [ ] **Step 1: Opt-in load tests (both ports' recall paths)**

Run: `mvn test -DexcludedGroups="" -Dgroups=load -Dtest=EmbeddingRecallLoadTest,OnlinePredictionLoadTest`
Expected: PASS — no regression (timeout default unchanged; only its source moved to the builder).

- [ ] **Step 2: No commit** (verification only).

---

## Self-Review

**Spec coverage:**
- Spec §3.1 retire `OnlineRecommendationEngine` + test → Task 3.
- Spec §3.2 env-tunable timeout in `RecallConfig.Builder` (`readLongEnv`, `RECALL_CHANNEL_TIMEOUT_MS`, default 200) → Task 1.
- Spec §3.3 `RecSysServer` drop call + remove constant → Task 2.
- Spec §3.4 `OnlinePredictionServer` drop call → Task 2.
- Spec §4 no-behavior-change-when-unset → Task 1 default test + Task 2 regression.
- Spec §5 testing (default + env-name contract, delete engine test, full suite) → Tasks 1, 3, 4.

**Placeholder scan:** none — every step has concrete code/commands.

**Type consistency:**
- `RecallConfig.readLongEnv(String,long)` defined Task 1, asserted Task 1.
- `RecallConfig.builder()...channelTimeoutMs()` accessor (existing record component) used in Task 1 test.
- `RECALL_CHANNEL_TIMEOUT_MS` and default `200L` consistent across Task 1 (builder + helper) and the spec.
- Task 2 references the exact existing call sites (`RecSysServer:105` / `:44`, `OnlinePredictionServer:93`) verified from source.
