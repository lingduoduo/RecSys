# Model Serving Recent-History Recall — Implementation Plan (Sub-project 3 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the Flink-written recent watch history as a retrieval channel on port 8080's model-serving recall, and auto-exclude the user's recent watches from recommendations.

**Architecture:** `ModelRuntimeProvider` builds one shared `OnlineFeatureStore` (on SP1's fail-fast recall pool) and adds `OnlineRecentHistoryChannel` to the recall channel set; `QuotaPolicy.defaultModelRetrieval` gives `online_recent_history` a warm quota slot. `ModelRetrievalStage` gains the recent-history store and unions the user's recent watched ids into the recall query's `excludedItemIds`. `RecommendationService`, `RankingStage`, and the controller are untouched. The ONNX model is unchanged (it can't ingest features).

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Mockito.

## Global Constraints

- Java 17, Maven. `mvn test -Dtest=<Class>` runs one class. Branch off `main` (SP1 #129 + SP2 #130 merged).
- **HTTP contract unchanged.** With no Redis (recent history empty), behavior is identical to SP1. Content changes when recent history is present (intended).
- **Reuse, don't rebuild:** `OnlineRecentHistoryChannel` (`com.recsys.service.retrieval.channels`) and `OnlineFeatureStore` (`com.recsys.online.store`, implements `RecentHistoryStore`) are existing classes — do NOT modify them. `RecentHistoryStore.getRecentMovieIds(int userId, int limit)` → `List<Integer>`.
- **Channel name `online_recent_history`** must match the quota-policy key.
- **Quota rebalance (`defaultModelRetrieval`):** warm `{embedding 0.55, online_recent_history 0.20, trending 0.10}` residual `popularity`; cold UNCHANGED `{cold_start 0.50, trending 0.25}` residual `popularity` (`online_recent_history` absent from cold → 0 cold slots).
- **Exclusion** lives in `ModelRetrievalStage` only (`RECENT_EXCLUDE_LIMIT = 20`); non-numeric userId or any store error → skip augmentation, never fail retrieval.
- Do NOT touch `RecommendationService`, `RankingStage`, the controller, `MultiChannelRecallService`/`RecallConfig`, the ONNX model, or SP1/SP2 behavior.

---

### Task 1: Rebalance `QuotaPolicy.defaultModelRetrieval()`

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/coldstart/QuotaPolicy.java`
- Test: `src/test/java/com/recsys/service/retrieval/coldstart/QuotaPolicyTest.java` (rework the existing `defaultModelRetrieval` warm test)

**Interfaces:**
- Produces: `QuotaPolicy.defaultModelRetrieval()` warm map now `{embedding 0.55, online_recent_history 0.20, trending 0.10}` residual `popularity`; cold unchanged.

- [ ] **Step 1: Update the existing `defaultModelRetrieval` warm test**

In `QuotaPolicyTest.java`, find the SP1 test that asserts the `defaultModelRetrieval` **warm** slots (it currently expects `embedding=14, trending=3, popularity=3` for limit 20, and `doesNotContainKey("cold_start")`). Replace its assertions with the rebalanced warm allocation, and add the `online_recent_history` checks:

```java
    @Test
    void defaultModelRetrieval_warmIncludesRecentHistory() {
        QuotaSpec warm = QuotaPolicy.defaultModelRetrieval().warm(20);
        assertThat(warm.slots().get("embedding")).isEqualTo(11);              // round(0.55 * 20)
        assertThat(warm.slots().get("online_recent_history")).isEqualTo(4);   // round(0.20 * 20)
        assertThat(warm.slots().get("trending")).isEqualTo(2);               // round(0.10 * 20)
        assertThat(warm.slots().get("popularity")).isEqualTo(3);            // residual 20-11-4-2
        assertThat(warm.slots().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
        assertThat(warm.slots()).doesNotContainKey("cold_start");
    }
```

Leave the existing `defaultModelRetrieval` **cold** test as-is, but add one assertion to it that `online_recent_history` is not in the cold slots (cold is unchanged: `cold_start=10, trending=5, popularity=5` for limit 20):

```java
        assertThat(cold.slots()).doesNotContainKey("online_recent_history");
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=QuotaPolicyTest`
Expected: FAIL — the current `defaultModelRetrieval` warm has no `online_recent_history` slot (embedding is still 14, not 11).

- [ ] **Step 3: Rebalance the factory** — in `QuotaPolicy.java`, replace the `defaultModelRetrieval()` warm map:

```java
    public static QuotaPolicy defaultModelRetrieval() {
        Map<String, Double> warm = new LinkedHashMap<>();
        warm.put("embedding", 0.55);
        warm.put("online_recent_history", 0.20);
        warm.put("trending", 0.10);
        Map<String, Double> cold = new LinkedHashMap<>();
        cold.put("cold_start", 0.50);
        cold.put("trending", 0.25);
        return new QuotaPolicy(warm, "popularity", cold, "popularity");
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=QuotaPolicyTest`
Expected: PASS (the equivalence/defaultMovie/defaultOnline tests are untouched and still pass).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/coldstart/QuotaPolicy.java \
        src/test/java/com/recsys/service/retrieval/coldstart/QuotaPolicyTest.java
git commit -m "feat: add online_recent_history warm slot to defaultModelRetrieval quota

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Recent-history exclusion + channel wiring

`ModelRetrievalStage` gains the recent-history store and excludes recent watches; `ModelRuntimeProvider` builds the shared `OnlineFeatureStore`, adds `OnlineRecentHistoryChannel`, and constructs the 2-arg stage. These change together so the build stays green.

**Files:**
- Modify: `src/main/java/com/recsys/model/service/ModelRetrievalStage.java`
- Modify: `src/main/java/com/recsys/model/service/ModelRuntimeProvider.java`
- Test: `src/test/java/com/recsys/model/service/ModelRetrievalStageTest.java` (rework)

**Interfaces:**
- Consumes: `RecentHistoryStore.getRecentMovieIds(int,int)` → `List<Integer>`; `OnlineFeatureStore(Pool<Jedis>)` implements `RecentHistoryStore`; `OnlineRecentHistoryChannel(RecentHistoryStore, DataManager)`; `QuotaPolicy.defaultModelRetrieval()` (Task 1); `RecommendationQuery(String userId, int limit, Set<String> excludedItemIds, String cursor)`.
- Produces: `ModelRetrievalStage(MultiChannelRecallService, RecentHistoryStore)` (the 1-arg ctor is removed).

- [ ] **Step 1: Rework `ModelRetrievalStageTest`** (write the new expectations first)

Replace the file with:

```java
package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.online.store.RecentHistoryStore;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRetrievalStageTest {

    private final MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
    private final RecentHistoryStore history = mock(RecentHistoryStore.class);
    private final ModelRetrievalStage stage = new ModelRetrievalStage(recall, history);

    @Test
    void retrieve_delegates_whenNoRecentHistory() {
        when(history.getRecentMovieIds(anyInt(), anyInt())).thenReturn(List.of());
        RecommendationQuery query = new RecommendationQuery("123", 50, Set.of("1"), null);
        List<MovieCandidate> expected = List.of(new MovieCandidate("4", 0.9, "trending", Map.of()));
        when(recall.recall(query, 50)).thenReturn(expected);

        assertThat(stage.retrieve(query, 50)).isEqualTo(expected);
    }

    @Test
    void retrieve_unionsRecentWatchesIntoExclusions() {
        when(history.getRecentMovieIds(eq(123), anyInt())).thenReturn(List.of(7, 8));
        RecommendationQuery query = new RecommendationQuery("123", 50, Set.of("1"), null);

        stage.retrieve(query, 50);

        ArgumentCaptor<RecommendationQuery> captor = ArgumentCaptor.forClass(RecommendationQuery.class);
        verify(recall).recall(captor.capture(), eq(50));
        assertThat(captor.getValue().excludedItemIds()).containsExactlyInAnyOrder("1", "7", "8");
        assertThat(captor.getValue().userId()).isEqualTo("123");
        assertThat(captor.getValue().limit()).isEqualTo(50);
    }

    @Test
    void retrieve_nonNumericUserId_skipsAugmentation() {
        RecommendationQuery query = new RecommendationQuery("alice", 50, Set.of("1"), null);

        stage.retrieve(query, 50);

        ArgumentCaptor<RecommendationQuery> captor = ArgumentCaptor.forClass(RecommendationQuery.class);
        verify(recall).recall(captor.capture(), eq(50));
        assertThat(captor.getValue().excludedItemIds()).containsExactly("1");
    }

    @Test
    void retrieve_storeError_skipsAugmentationGracefully() {
        when(history.getRecentMovieIds(anyInt(), anyInt())).thenThrow(new RuntimeException("redis down"));
        RecommendationQuery query = new RecommendationQuery("123", 50, Set.of("1"), null);

        stage.retrieve(query, 50);   // must not throw

        ArgumentCaptor<RecommendationQuery> captor = ArgumentCaptor.forClass(RecommendationQuery.class);
        verify(recall).recall(captor.capture(), eq(50));
        assertThat(captor.getValue().excludedItemIds()).containsExactly("1");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=ModelRetrievalStageTest`
Expected: COMPILATION FAILURE — `ModelRetrievalStage(MultiChannelRecallService, RecentHistoryStore)` does not exist.

- [ ] **Step 3: Update `ModelRetrievalStage`** — replace the class body:

```java
package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.online.store.RecentHistoryStore;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Per-variant retrieval stage: delegates candidate generation to the shared
 * {@link MultiChannelRecallService}, and excludes the user's recent watched movies from the
 * results (so just-watched titles do not reappear). Warm/cold classification is resolved inside
 * the recall service via the per-variant {@link VocabMembershipEmbeddingStore} wired in
 * {@code RecallConfig}.
 */
public class ModelRetrievalStage {

    private static final int RECENT_EXCLUDE_LIMIT = 20;

    private final MultiChannelRecallService recallService;
    private final RecentHistoryStore recentHistoryStore;

    public ModelRetrievalStage(MultiChannelRecallService recallService, RecentHistoryStore recentHistoryStore) {
        this.recallService = Objects.requireNonNull(recallService, "recallService");
        this.recentHistoryStore = Objects.requireNonNull(recentHistoryStore, "recentHistoryStore");
    }

    public List<MovieCandidate> retrieve(RecommendationQuery query, int limit) {
        return recallService.recall(withRecentExclusions(query), limit);
    }

    /**
     * Adds the user's recent watched ids to the query's excluded set. Non-numeric userId or any
     * store/Redis error skips augmentation — retrieval must never fail on the recent-history path.
     */
    private RecommendationQuery withRecentExclusions(RecommendationQuery query) {
        try {
            int userId = Integer.parseInt(query.userId());
            List<Integer> recent = recentHistoryStore.getRecentMovieIds(userId, RECENT_EXCLUDE_LIMIT);
            if (recent.isEmpty()) {
                return query;
            }
            Set<String> excluded = new HashSet<>(query.excludedItemIds());
            for (Integer id : recent) {
                excluded.add(String.valueOf(id));
            }
            return new RecommendationQuery(query.userId(), query.limit(), excluded, query.cursor());
        } catch (NumberFormatException e) {
            return query;
        } catch (RuntimeException e) {
            return query;
        }
    }
}
```

- [ ] **Step 4: Run the stage test to verify it passes**

Run: `mvn test -Dtest=ModelRetrievalStageTest`
Expected: COMPILATION FAILURE elsewhere — `ModelRuntimeProvider` still calls the removed 1-arg ctor (fixed next step). If run in isolation it won't compile the module; proceed to Step 5, then re-run.

- [ ] **Step 5: Wire `OnlineFeatureStore` + the channel in `ModelRuntimeProvider`**

(a) Add imports:
```java
import com.recsys.online.store.OnlineFeatureStore;
import com.recsys.service.retrieval.channels.OnlineRecentHistoryChannel;
```
(b) Add a field next to the other recall fields (`private GlobalPopularityStore globalPopStore;` etc.):
```java
    private OnlineFeatureStore onlineFeatureStore;
```
(c) In `ensureRecallInfra()`, after `globalPopStore = new GlobalPopularityStore(recallPool);`, add:
```java
            onlineFeatureStore = new OnlineFeatureStore(recallPool);
```
(d) In `buildRecallService(...)`, add the channel to the list (between `EmbeddingChannel` and `TrendingChannel`):
```java
                                new EmbeddingChannel(candidateGenerator),
                                new OnlineRecentHistoryChannel(onlineFeatureStore, DataManager.getInstance()),
                                new TrendingChannel(topkStore, java.util.List.of("last_hour", "last_day")),
```
(e) In `buildRuntime(...)`, change the retrieval-stage construction to the 2-arg ctor:
```java
            ModelRetrievalStage retrievalStage = new ModelRetrievalStage(buildRecallService(artifactService), onlineFeatureStore);
```

- [ ] **Step 6: Compile + run the stage test + the model-runtime + integration suites**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

Run: `mvn test -Dtest=ModelRetrievalStageTest,ModelRuntimeProviderTest,PredictionIntegrationTest,RecommendationServiceTest`
Expected: PASS. `ModelRuntimeProviderTest` passes unmodified — it already asserts a runtime builds (which now constructs the recent-history channel + `OnlineFeatureStore`, proving the wiring compiles and builds). Integration/service tests run with no Redis → recent history empty → no exclusion → SP1 behavior preserved.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/model/service/ModelRetrievalStage.java \
        src/main/java/com/recsys/model/service/ModelRuntimeProvider.java \
        src/test/java/com/recsys/model/service/ModelRetrievalStageTest.java
git commit -m "feat: recent-history recall channel + recent-watch exclusion on port 8080

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Full-suite + load guard

**Files:** none (verification only).

- [ ] **Step 1: Full build + test suite**

Run: `mvn test`
Expected: BUILD SUCCESS — all tests pass (SP1/SP2 recall, ranking, A/B, controller, cache tests unaffected; `OnlineRecentHistoryChannelTest` passes as the channel is reused unchanged).

- [ ] **Step 2: Load guard (8080 recommend path with one more recall channel)**

Run: `mvn test -DexcludedGroups="" -Dgroups=load -Dtest=InferenceLoadTest`
Expected: PASS — P95 ≤ 2000 ms. One more channel runs on the parallel recall path within the 200 ms per-channel budget + SP1's fail-fast recall pool (150 ms Redis timeout); recent-history reads fail fast to empty when Redis is absent.

- [ ] **Step 3: No commit** (verification only).

---

## Self-Review

**Spec coverage:**
- §4.1 `ModelRuntimeProvider` builds `OnlineFeatureStore` + adds `OnlineRecentHistoryChannel` + 2-arg `ModelRetrievalStage` → Task 2 Step 5.
- §4.2 `ModelRetrievalStage` 2-arg ctor + recent-watch exclusion (graceful on non-numeric/error) → Task 2 Steps 3,1.
- §4.3 `QuotaPolicy.defaultModelRetrieval` warm rebalance → Task 1.
- §4.4 `OnlineRecentHistoryChannel` reused unchanged → Task 2 (no new code for it).
- §5/§6 behavior + error handling (no history → gap-fill; non-numeric/error → skip; total-empty → SP1 fallback) → Task 2 tests + unchanged SP1 paths.
- §7 testing → Tasks 1–3. Note: `ModelRuntimeProviderTest` is covered by passing **unmodified** (the existing build assertion now exercises the new channel wiring); no contrived new assertion is added, which is the honest reading of "runtime builds with the channel wired."

**Placeholder scan:** none — every step has concrete code/commands.

**Type consistency:**
- `QuotaPolicy.defaultModelRetrieval()` warm keys (`embedding`, `online_recent_history`, `trending`, residual `popularity`) — Task 1; the `online_recent_history` key matches `OnlineRecentHistoryChannel.name()` and the channel added in Task 2.
- `ModelRetrievalStage(MultiChannelRecallService, RecentHistoryStore)` / `retrieve(RecommendationQuery, int)` — defined Task 2 Step 3, called Task 2 Step 5(e).
- `OnlineFeatureStore(Pool<Jedis>)` implements `RecentHistoryStore`; `getRecentMovieIds(int,int)` → `List<Integer>` — used Task 2 Steps 3,5.
- `OnlineRecentHistoryChannel(RecentHistoryStore, DataManager)` — used Task 2 Step 5(d).
- `RecommendationQuery(String,int,Set<String>,String)` reconstruction — Task 2 Step 3.
