# Model Serving Retrieval→Ranking via Shared Recall — Implementation Plan (Sub-project 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure port 8080's recommend path into an explicit retrieve → rank → hybrid-merge pipeline, where retrieval is the shared `MultiChannelRecallService` and ranking is the ONNX two-tower model, orchestrated per variant by `ModelRuntimeProvider`.

**Architecture:** Each `ModelRuntime` gains a `ModelRetrievalStage` (shared recall, per-variant warm/cold via model vocab membership) and a `RankingStage` (ONNX hybrid two-tier: in-vocab ONNX-scored above out-of-vocab kept at recall score). `ModelRuntimeProvider` builds the shared recall stores (`CandidateGenerator`, `ShardedTopKStore`, `GlobalPopularityStore`, executor) once and wires the per-variant stages. `RecommendationService` switches from `CandidateSelectionService → scoreCandidates` to the new pipeline, keeping `CandidateSelectionService` only as the empty-recall (Redis-down) fallback.

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Mockito, ONNX Runtime.

## Global Constraints

- Java 17, Maven. `mvn test -Dtest=<Class>` runs one class. `mvn spring-boot:run` starts 8080.
- **HTTP contract unchanged:** `POST /api/v1/recommend` request and `RecommendResponse{userId, modelVersion, abTestVariant, recommendations}` shape stay byte-compatible. Recommendation *content* changes (intended).
- **Cold-start source of truth = model vocab membership.** 8080 passes its vocab-based warm/cold into recall via a `VocabMembershipEmbeddingStore` (an `EmbeddingStore` whose `getEmbedding(int)` is non-null iff the id is a vocab member). No change to `MultiChannelRecallService`.
- **Strict hybrid tiering:** ONNX-scored in-vocab items always rank above out-of-vocab items (kept at their recall score). No cross-scale calibration.
- **`recallLimit` cap is 100** (the shared `RecommendationQuery` limit). Use `min(max(k*5, 50), 100)`.
- Reuse the 7010 recall channel set/wiring (`OnlinePredictionServer`) as the template. Do **not** modify `MultiChannelRecallService`, `RecallConfig`, or `QuotaPolicy`'s existing factories.
- A/B reliability and real-time user-tower features are out of scope (SP2 / SP3).

---

### Task 1: `QuotaPolicy.defaultModelRetrieval()`

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/coldstart/QuotaPolicy.java`
- Test: `src/test/java/com/recsys/service/retrieval/coldstart/QuotaPolicyTest.java` (extend)

**Interfaces:**
- Produces: `static QuotaPolicy QuotaPolicy.defaultModelRetrieval()` — warm `{embedding 0.70, trending 0.15}` residual `popularity`; cold `{cold_start 0.50, trending 0.25}` residual `popularity`.

- [ ] **Step 1: Write the failing tests** — append inside `QuotaPolicyTest`:

```java
    @Test
    void defaultModelRetrieval_warmIsEmbeddingLed() {
        QuotaSpec warm = QuotaPolicy.defaultModelRetrieval().warm(20);
        assertThat(warm.slots().get("embedding")).isEqualTo(14);   // round(0.70 * 20)
        assertThat(warm.slots().get("trending")).isEqualTo(3);     // round(0.15 * 20)
        assertThat(warm.slots().get("popularity")).isEqualTo(3);   // residual 20-14-3
        assertThat(warm.slots().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
        assertThat(warm.slots()).doesNotContainKey("cold_start");  // cold_start has 0 warm slots
    }

    @Test
    void defaultModelRetrieval_coldIsColdStartLed() {
        QuotaSpec cold = QuotaPolicy.defaultModelRetrieval().cold(20);
        assertThat(cold.slots().get("cold_start")).isEqualTo(10);  // round(0.50 * 20)
        assertThat(cold.slots().get("trending")).isEqualTo(5);     // round(0.25 * 20)
        assertThat(cold.slots().get("popularity")).isEqualTo(5);   // residual 20-10-5
        assertThat(cold.slots()).doesNotContainKey("embedding");   // embedding has 0 cold slots
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=QuotaPolicyTest`
Expected: COMPILATION FAILURE — `QuotaPolicy.defaultModelRetrieval()` does not exist.

- [ ] **Step 3: Add the factory** — in `QuotaPolicy.java`, after `defaultMovie()` (around line 89), add:

```java
    /** Port-8080 model-serving retrieval quota: embedding-led warm (the ONNX ranker personalizes), cold-start led cold. */
    public static QuotaPolicy defaultModelRetrieval() {
        Map<String, Double> warm = new LinkedHashMap<>();
        warm.put("embedding", 0.70);
        warm.put("trending", 0.15);
        Map<String, Double> cold = new LinkedHashMap<>();
        cold.put("cold_start", 0.50);
        cold.put("trending", 0.25);
        return new QuotaPolicy(warm, "popularity", cold, "popularity");
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=QuotaPolicyTest`
Expected: PASS (existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/coldstart/QuotaPolicy.java \
        src/test/java/com/recsys/service/retrieval/coldstart/QuotaPolicyTest.java
git commit -m "feat: add QuotaPolicy.defaultModelRetrieval() for port 8080 retrieval

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `VocabMembershipEmbeddingStore`

**Files:**
- Create: `src/main/java/com/recsys/model/service/VocabMembershipEmbeddingStore.java`
- Test: `src/test/java/com/recsys/model/service/VocabMembershipEmbeddingStoreTest.java`

**Interfaces:**
- Consumes: `com.recsys.infrastructure.vectordb.EmbeddingStore` (method used by recall: `float[] getEmbedding(int id)`).
- Produces: `new VocabMembershipEmbeddingStore(Map<String,Integer> userVocab)`; `getEmbedding(int id)` returns a non-null sentinel iff `Integer.toString(id)` is a vocab key (excluding `__UNK__`), else `null`. Other `EmbeddingStore` methods are inert (recall only calls `getEmbedding(int)`).

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.model.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VocabMembershipEmbeddingStoreTest {

    @Test
    void presentForVocabMember_nullForUnknownAndUnk() {
        var store = new VocabMembershipEmbeddingStore(Map.of("123", 1, "200", 2, "__UNK__", 0));
        assertThat(store.getEmbedding(123)).isNotNull();   // in vocab -> warm
        assertThat(store.getEmbedding(200)).isNotNull();
        assertThat(store.getEmbedding(999)).isNull();      // not in vocab -> cold
        assertThat(store.getEmbedding(0)).isNull();        // "0" is not a member (__UNK__ excluded)
    }

    @Test
    void inertWriteAndBulkMethodsDoNotThrow() {
        var store = new VocabMembershipEmbeddingStore(Map.of("1", 1));
        assertThat(store.getEmbeddings(java.util.List.of(1, 2))).isEmpty();
        assertThat(store.scanIds(10)).isEmpty();
        store.setEmbedding(1, new float[]{0f}, 0L);                 // no-op
        store.setEmbeddings(Map.of(1, new float[]{0f}), 0L);       // no-op
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=VocabMembershipEmbeddingStoreTest`
Expected: COMPILATION FAILURE — class does not exist.

- [ ] **Step 3: Create the class**

```java
package com.recsys.model.service;

import com.recsys.infrastructure.vectordb.EmbeddingStore;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Adapts the ONNX model's user vocab into an {@link EmbeddingStore} for warm/cold classification.
 * {@code MultiChannelRecallService} treats a user as cold when {@code getEmbedding(parseInt(userId))}
 * is null; this makes cold ⇔ "not in the model user vocab" (the port-8080 source of truth), without
 * changing the shared recall service. The sentinel vector is never used for ANN — the embedding
 * channel uses a separate {@code CandidateGenerator} store.
 */
public class VocabMembershipEmbeddingStore implements EmbeddingStore {

    private static final float[] PRESENT = new float[]{1.0f};

    private final Set<String> members;

    public VocabMembershipEmbeddingStore(Map<String, Integer> userVocab) {
        Set<String> copy = new HashSet<>(userVocab.keySet());
        copy.remove("__UNK__");
        this.members = Set.copyOf(copy);
    }

    @Override
    public float[] getEmbedding(int id) {
        return members.contains(Integer.toString(id)) ? PRESENT : null;
    }

    @Override
    public Map<Integer, float[]> getEmbeddings(Collection<Integer> ids) {
        return Map.of();
    }

    @Override
    public void setEmbedding(int id, float[] vector, long ttlSeconds) {
        // inert: this adapter is read-only membership, recall never writes through it
    }

    @Override
    public void setEmbeddings(Map<Integer, float[]> vectors, long ttlSeconds) {
        // inert
    }

    @Override
    public Set<Integer> scanIds(int maxKeys) {
        return Set.of();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=VocabMembershipEmbeddingStoreTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/model/service/VocabMembershipEmbeddingStore.java \
        src/test/java/com/recsys/model/service/VocabMembershipEmbeddingStoreTest.java
git commit -m "feat: add VocabMembershipEmbeddingStore (vocab-based warm/cold for 8080 recall)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `RankingStage` (ONNX hybrid two-tier)

**Files:**
- Create: `src/main/java/com/recsys/model/service/RankingStage.java`
- Test: `src/test/java/com/recsys/model/service/RankingStageTest.java`

(The dormant `RankingService` is deleted in Task 6; `RankingStage` replaces it.)

**Interfaces:**
- Consumes: `UserTowerInferenceService.scoreCandidates(FeatureEncoder.EncodedFeatures, FeatureEncoder, Set<String>, int)` → `List<ScoredItem>`; `ModelArtifactService.getItemVocab()` → `Map<String,Integer>`; `com.recsys.domain.MovieCandidate` (`itemId()`, `score()`); `com.recsys.model.dto.ScoredItem`.
- Produces: `new RankingStage(UserTowerInferenceService, FeatureEncoder, ModelArtifactService)`; `List<ScoredItem> rank(FeatureEncoder.EncodedFeatures user, List<MovieCandidate> candidates, int k)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.model.dto.ScoredItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RankingStageTest {

    private RankingStage stageWith(Map<String, Integer> itemVocab, List<ScoredItem> onnxResult) {
        UserTowerInferenceService inference = mock(UserTowerInferenceService.class);
        ModelArtifactService artifacts = mock(ModelArtifactService.class);
        when(artifacts.getItemVocab()).thenReturn(itemVocab);
        when(artifacts.getUserVocab()).thenReturn(Map.of("__UNK__", 0));
        FeatureEncoder encoder = new FeatureEncoder(artifacts);
        when(inference.scoreCandidates(any(), any(), anySet(), anyInt())).thenReturn(onnxResult);
        return new RankingStage(inference, encoder, artifacts);
    }

    @Test
    void inVocabOnnxRanked_outOfVocabAppendedByRecallScore() {
        RankingStage stage = stageWith(
                Map.of("1", 1, "2", 2),                                  // 1,2 in vocab; 3,4 out
                List.of(new ScoredItem("2", 9.0), new ScoredItem("1", 5.0)));
        List<MovieCandidate> candidates = List.of(
                new MovieCandidate("1", 0.3, "embedding", Map.of()),
                new MovieCandidate("2", 0.2, "embedding", Map.of()),
                new MovieCandidate("3", 0.8, "trending", Map.of()),     // out-of-vocab, high recall score
                new MovieCandidate("4", 0.1, "popularity", Map.of()));

        List<ScoredItem> ranked = stage.rank(new FeatureEncoder.EncodedFeatures(0), candidates, 4);

        assertThat(ranked).extracting(ScoredItem::itemId).containsExactly("2", "1", "3", "4");
    }

    @Test
    void modelItemsFillK_dropsOutOfVocab() {
        RankingStage stage = stageWith(
                Map.of("1", 1, "2", 2),
                List.of(new ScoredItem("2", 9.0), new ScoredItem("1", 5.0)));
        List<MovieCandidate> candidates = List.of(
                new MovieCandidate("1", 0.3, "embedding", Map.of()),
                new MovieCandidate("2", 0.2, "embedding", Map.of()),
                new MovieCandidate("3", 0.8, "trending", Map.of()));

        List<ScoredItem> ranked = stage.rank(new FeatureEncoder.EncodedFeatures(0), candidates, 2);

        assertThat(ranked).extracting(ScoredItem::itemId).containsExactly("2", "1"); // out-of-vocab 3 dropped
    }

    @Test
    void emptyCandidatesOrNonPositiveK_returnsEmpty() {
        RankingStage stage = stageWith(Map.of("1", 1), List.of());
        assertThat(stage.rank(new FeatureEncoder.EncodedFeatures(0), List.of(), 5)).isEmpty();
        assertThat(stage.rank(new FeatureEncoder.EncodedFeatures(0),
                List.of(new MovieCandidate("1", 0.5, "c", Map.of())), 0)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=RankingStageTest`
Expected: COMPILATION FAILURE — `RankingStage` does not exist.

- [ ] **Step 3: Create the class**

```java
package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.model.dto.ScoredItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Hybrid two-tier ranking. ONNX-scores the candidates that are in the model item vocab (tier 1,
 * sorted by model score); candidates outside the vocab keep their recall score (tier 2, appended
 * below). Strict tiering — the model's known items always rank above fresh/unknown ones — so the
 * two score scales never need reconciling. Replaces the legacy inner-product RankingService.
 */
public class RankingStage {

    private final UserTowerInferenceService inference;
    private final FeatureEncoder featureEncoder;
    private final ModelArtifactService artifactService;

    public RankingStage(UserTowerInferenceService inference,
                        FeatureEncoder featureEncoder,
                        ModelArtifactService artifactService) {
        this.inference = inference;
        this.featureEncoder = featureEncoder;
        this.artifactService = artifactService;
    }

    public List<ScoredItem> rank(FeatureEncoder.EncodedFeatures user, List<MovieCandidate> candidates, int k) {
        if (candidates == null || candidates.isEmpty() || k <= 0) {
            return List.of();
        }
        Map<String, Integer> itemVocab = artifactService.getItemVocab();

        LinkedHashSet<String> inVocab = new LinkedHashSet<>();
        List<MovieCandidate> outOfVocab = new ArrayList<>();
        for (MovieCandidate c : candidates) {
            if (itemVocab.containsKey(c.itemId())) {
                inVocab.add(c.itemId());
            } else {
                outOfVocab.add(c);
            }
        }

        // Tier 1: ONNX score the in-vocab candidates (already returned sorted desc, capped at k).
        List<ScoredItem> tier1 = inference.scoreCandidates(user, featureEncoder, inVocab, k);

        // Tier 2: out-of-vocab kept at their recall score, highest first.
        List<ScoredItem> tier2 = outOfVocab.stream()
                .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed())
                .map(c -> new ScoredItem(c.itemId(), c.score()))
                .toList();

        Map<String, ScoredItem> merged = new LinkedHashMap<>();
        for (ScoredItem s : tier1) merged.putIfAbsent(s.itemId(), s);
        for (ScoredItem s : tier2) merged.putIfAbsent(s.itemId(), s);
        return merged.values().stream().limit(k).toList();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=RankingStageTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/model/service/RankingStage.java \
        src/test/java/com/recsys/model/service/RankingStageTest.java
git commit -m "feat: add RankingStage (ONNX hybrid two-tier ranking)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `ModelRetrievalStage`

**Files:**
- Create: `src/main/java/com/recsys/model/service/ModelRetrievalStage.java`
- Test: `src/test/java/com/recsys/model/service/ModelRetrievalStageTest.java`

**Interfaces:**
- Consumes: `MultiChannelRecallService.recall(RecommendationQuery, int)` → `List<MovieCandidate>`.
- Produces: `new ModelRetrievalStage(MultiChannelRecallService)`; `List<MovieCandidate> retrieve(RecommendationQuery query, int limit)`.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRetrievalStageTest {

    @Test
    void retrieve_delegatesToRecallService() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        RecommendationQuery query = new RecommendationQuery("123", 50, Set.of(), null);
        List<MovieCandidate> expected = List.of(new MovieCandidate("4", 0.9, "trending", Map.of()));
        when(recall.recall(query, 50)).thenReturn(expected);

        ModelRetrievalStage stage = new ModelRetrievalStage(recall);

        assertThat(stage.retrieve(query, 50)).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=ModelRetrievalStageTest`
Expected: COMPILATION FAILURE — `ModelRetrievalStage` does not exist.

- [ ] **Step 3: Create the class**

```java
package com.recsys.model.service;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;

import java.util.List;
import java.util.Objects;

/**
 * Per-variant retrieval stage: delegates candidate generation to the shared
 * {@link MultiChannelRecallService}. Warm/cold classification is resolved inside the recall
 * service via the per-variant {@link VocabMembershipEmbeddingStore} wired in {@code RecallConfig}.
 */
public class ModelRetrievalStage {

    private final MultiChannelRecallService recallService;

    public ModelRetrievalStage(MultiChannelRecallService recallService) {
        this.recallService = Objects.requireNonNull(recallService, "recallService");
    }

    public List<MovieCandidate> retrieve(RecommendationQuery query, int limit) {
        return recallService.recall(query, limit);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=ModelRetrievalStageTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/model/service/ModelRetrievalStage.java \
        src/test/java/com/recsys/model/service/ModelRetrievalStageTest.java
git commit -m "feat: add ModelRetrievalStage (shared recall wrapper for 8080)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Wire the pipeline — `ModelRuntime`, `ModelRuntimeProvider`, `RecommendationService`

This is the atomic switch from `CandidateSelectionService → scoreCandidates` to retrieve → rank → hybrid. The three files change together so the build stays green.

**Files:**
- Modify: `src/main/java/com/recsys/model/service/ModelRuntime.java`
- Modify: `src/main/java/com/recsys/model/service/ModelRuntimeProvider.java`
- Modify: `src/main/java/com/recsys/model/service/RecommendationService.java`
- Test: `src/test/java/com/recsys/model/service/ModelRuntimeProviderTest.java` (extend), `src/test/java/com/recsys/model/service/RecommendationServiceTest.java` (rework)

**Interfaces:**
- Consumes: `ModelRetrievalStage` (Task 4), `RankingStage` (Task 3), `VocabMembershipEmbeddingStore` (Task 2), `QuotaPolicy.defaultModelRetrieval()` (Task 1), and the shared recall wiring classes (`CandidateGenerator`, `ShardedTopKStore`, `GlobalPopularityStore`, `EmbeddingChannel`, `TrendingChannel`, `PopularityChannel`, `ColdStartChannel`, `RecallConfig`, `MultiChannelRecallService`, `ChannelHealthMonitor`, `FaultInjector`, `RedisConnectionFactory`, `RedisEmbeddingStore`).
- Produces: `ModelRuntime.retrievalStage()` / `ModelRuntime.rankingStage()` accessors; `ModelRuntime` no longer has `candidateSelectionService()`.

- [ ] **Step 1: Change `ModelRuntime` to carry the two stages**

Replace the whole record body of `ModelRuntime.java` with:

```java
package com.recsys.model.service;

record ModelRuntime(
        String variant,
        ModelArtifactService artifactService,
        ModelRetrievalStage retrievalStage,
        RankingStage rankingStage,
        FeatureEncoder featureEncoder,
        UserTowerInferenceService inferenceService
) {
    String modelVersion() {
        return artifactService.getModelVersion();
    }

    boolean isReady() {
        return inferenceService.isReady();
    }
}
```

- [ ] **Step 2: Wire the stages in `ModelRuntimeProvider`**

(a) Add imports near the existing imports:

```java
import com.recsys.infrastructure.DataManager;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.online.ops.FaultInjector;
import com.recsys.online.store.TrendingStore;
import com.recsys.infrastructure.redis.GlobalPopularityStore;
import com.recsys.infrastructure.redis.ShardedTopKStore;
import com.recsys.service.retrieval.channels.EmbeddingChannel;
import com.recsys.service.retrieval.channels.PopularityChannel;
import com.recsys.service.retrieval.channels.TrendingChannel;
import com.recsys.service.retrieval.coldstart.ColdStartChannel;
import com.recsys.service.retrieval.coldstart.QuotaPolicy;
import com.recsys.service.retrieval.multichannel.ChannelHealthMonitor;
import com.recsys.service.retrieval.multichannel.MultiChannelRecallService;
import com.recsys.service.retrieval.multichannel.RecallConfig;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
```

(b) Add shared-recall fields next to `private Pool<Jedis> redisItemEmbeddingPool;`:

```java
    private Pool<Jedis> recallPool;
    private CandidateGenerator candidateGenerator;
    private TrendingStore topkStore;
    private GlobalPopularityStore globalPopStore;
    private ExecutorService recallExecutor;
    private ChannelHealthMonitor sharedHealthMonitor;
    private final Object recallLock = new Object();
```

(c) Add the lazy shared-infra builder (place above `buildRuntime`):

```java
    /**
     * Builds the recall stores once and shares them across variants. Jedis pools and the
     * Redis-backed stores connect lazily, so this never fails when Redis is unavailable —
     * a request-time recall miss falls back to CandidateSelectionService in RecommendationService.
     */
    private void ensureRecallInfra() {
        if (candidateGenerator != null) return;
        synchronized (recallLock) {
            if (candidateGenerator != null) return;
            recallPool = RedisConnectionFactory.fromEnv();
            DataManager dataManager = DataManager.getInstance();
            candidateGenerator = new CandidateGenerator(dataManager, new RedisEmbeddingStore(recallPool, "u2vEmb"));
            topkStore = new ShardedTopKStore(recallPool, "topk:");
            globalPopStore = new GlobalPopularityStore(recallPool);
            recallExecutor = Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors() * 2,
                    r -> new Thread(r, "model-recall-channel"));
            sharedHealthMonitor = new ChannelHealthMonitor();
        }
    }

    private MultiChannelRecallService buildRecallService(ModelArtifactService artifactService) {
        ensureRecallInfra();
        return MultiChannelRecallService.from(
                RecallConfig.builder()
                        .channels(java.util.List.of(
                                new EmbeddingChannel(candidateGenerator),
                                new TrendingChannel(topkStore, java.util.List.of("last_hour", "last_day")),
                                new PopularityChannel(DataManager.getInstance(), globalPopStore),
                                new ColdStartChannel(topkStore, globalPopStore)))
                        .quotaPolicy(QuotaPolicy.defaultModelRetrieval())
                        .healthMonitor(sharedHealthMonitor)
                        .executor(recallExecutor)
                        .faultInjector(FaultInjector.NOOP)
                        .userEmbeddingStore(new VocabMembershipEmbeddingStore(artifactService.getUserVocab()))
                        .build());
    }
```

(d) Replace the `return new ModelRuntime(...)` block inside `buildRuntime` with:

```java
            FeatureEncoder featureEncoder = new FeatureEncoder(artifactService);
            ModelRetrievalStage retrievalStage = new ModelRetrievalStage(buildRecallService(artifactService));
            RankingStage rankingStage = new RankingStage(inferenceService, featureEncoder, artifactService);

            return new ModelRuntime(
                    variant,
                    artifactService,
                    retrievalStage,
                    rankingStage,
                    featureEncoder,
                    inferenceService
            );
```

(e) In `close()`, before `runtimes.clear();`, add executor + pool shutdown:

```java
        if (recallExecutor != null) {
            recallExecutor.shutdownNow();
            recallExecutor = null;
        }
        if (recallPool != null) {
            recallPool.close();
            recallPool = null;
        }
```

- [ ] **Step 3: Switch `RecommendationService` to retrieve → rank → hybrid**

(a) Add imports:

```java
import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import java.util.ArrayList;
```

(b) Replace the constants block (`RECALL_MULTIPLIER` / `MAX_RECALL_SIZE`) with:

```java
    private static final int RECALL_MULTIPLIER = 5;
    private static final int MIN_RECALL_SIZE = 50;
    private static final int MAX_RECALL_LIMIT = 100; // shared RecommendationQuery cap
```

(c) Replace `computeRecommendations(...)` with:

```java
    private List<ScoredItem> computeRecommendations(
            RecommendRequest request,
            ModelRuntime runtime,
            List<String> excludedItemIds
    ) {
        FeatureEncoder.EncodedFeatures encoded = runtime.featureEncoder().encode(request);
        Set<String> excluded = excludedItemIds.isEmpty() ? Set.of() : new HashSet<>(excludedItemIds);
        int recallLimit = Math.min(Math.max(request.getK() * RECALL_MULTIPLIER, MIN_RECALL_SIZE), MAX_RECALL_LIMIT);
        var query = new RecommendationQuery(request.getUserId(), recallLimit, excluded, null);

        List<MovieCandidate> candidates = runtime.retrievalStage().retrieve(query, recallLimit);
        if (candidates.isEmpty()) {
            candidates = fallbackCandidates(runtime, parseUserId(request.getUserId()), excluded);
        }
        return runtime.rankingStage().rank(encoded, candidates, request.getK());
    }
```

(d) Replace `computeColdStartPool(...)` with:

```java
    private List<ScoredItem> computeColdStartPool(RecommendRequest request, ModelRuntime runtime) {
        var coldStartRequest = new RecommendRequest();
        coldStartRequest.setUserId(request.getUserId());
        coldStartRequest.setK(cache.coldStartMaxK());

        FeatureEncoder.EncodedFeatures encoded = runtime.featureEncoder().encode(coldStartRequest);
        int recallLimit = Math.min(Math.max(cache.coldStartMaxK(), MIN_RECALL_SIZE), MAX_RECALL_LIMIT);
        var query = new RecommendationQuery(request.getUserId(), recallLimit, Set.of(), null);

        List<MovieCandidate> candidates = runtime.retrievalStage().retrieve(query, recallLimit);
        if (candidates.isEmpty()) {
            candidates = fallbackCandidates(runtime, null, Set.of());
        }
        return runtime.rankingStage().rank(encoded, candidates, cache.coldStartMaxK());
    }
```

(e) Add the fallback helper (next to `parseUserId`):

```java
    /**
     * Redis-down fallback: the in-memory CandidateSelectionService pool, wrapped as MovieCandidates
     * (score 0.0 — these are all in-vocab so the RankingStage re-scores them via ONNX, tier 1).
     */
    private static List<MovieCandidate> fallbackCandidates(ModelRuntime runtime, Integer numericUserId, Set<String> excluded) {
        Set<String> ids = new CandidateSelectionService(runtime.artifactService()).selectCandidates(numericUserId, excluded);
        List<MovieCandidate> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(new MovieCandidate(id, 0.0, "fallback", Map.of()));
        }
        return out;
    }
```

- [ ] **Step 4: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (no reference to the removed `candidateSelectionService()` accessor remains; `RetrievalService`/`RankingService` still exist and compile — deleted in Task 6).

- [ ] **Step 5: Extend `ModelRuntimeProviderTest`** — add a wiring assertion (uses the existing test's provider/locator setup; mirror how it builds a provider):

```java
    @Test
    void buildsRetrievalAndRankingStagesPerVariant() {
        ModelRuntime runtime = provider.getRuntime("training");
        assertThat(runtime.retrievalStage()).isNotNull();
        assertThat(runtime.rankingStage()).isNotNull();
        assertThat(runtime.artifactService()).isNotNull();
    }
```

(If the existing test field is not named `provider`, use the same construction the other tests in the file use to obtain a `ModelRuntimeProvider`.)

- [ ] **Step 6: Rework `RecommendationServiceTest`** — the recommend path now flows through retrieve→rank; in a unit env without Redis, recall is empty so the `CandidateSelectionService` fallback + ONNX ranking serve. Update existing expectations and add:

```java
    @Test
    void existingUser_servedViaFallbackWhenRecallEmpty_returnsRankedItems() {
        // No Redis in the unit env -> retrievalStage returns empty -> CandidateSelectionService fallback,
        // then RankingStage ONNX-scores the in-vocab pool. Assert a non-empty, k-bounded response.
        RecommendRequest request = new RecommendRequest();
        request.setUserId(KNOWN_USER_ID);   // a user present in the test model's user vocab
        request.setK(5);

        RecommendResponse response = service.recommend(request);

        assertThat(response.recommendations()).isNotEmpty();
        assertThat(response.recommendations().size()).isLessThanOrEqualTo(5);
        assertThat(response.abTestVariant()).isNotBlank();
    }
```

Keep the existing cache-hit/miss, cold-start, exclusion, and degradation tests; adjust any that asserted the old `CandidateSelectionService`-driven ordering to assert response shape / size / membership instead (the candidate source changed). Use the same test fixtures (`ModelRuntimeProvider`, `ABTestService`) the file already wires.

- [ ] **Step 7: Run the reworked tests**

Run: `mvn test -Dtest=ModelRuntimeProviderTest,RecommendationServiceTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/model/service/ModelRuntime.java \
        src/main/java/com/recsys/model/service/ModelRuntimeProvider.java \
        src/main/java/com/recsys/model/service/RecommendationService.java \
        src/test/java/com/recsys/model/service/ModelRuntimeProviderTest.java \
        src/test/java/com/recsys/model/service/RecommendationServiceTest.java
git commit -m "feat: wire 8080 recommend path through retrieve->rank->hybrid (shared recall)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Retire the dormant `RetrievalService` and legacy `RankingService`

**Files:**
- Delete: `src/main/java/com/recsys/model/service/RetrievalService.java`
- Delete: `src/test/java/com/recsys/model/service/RetrievalServiceTest.java`
- Delete: `src/main/java/com/recsys/model/service/RankingService.java`
- Delete: `src/test/java/com/recsys/model/service/RankingServiceTest.java`

**Interfaces:** Consumes nothing. Produces: removal of dead code (`RankingService` is superseded by `RankingStage`; `RetrievalService` by `ModelRetrievalStage` + shared recall).

- [ ] **Step 1: Confirm no remaining references**

Run: `grep -rn "new RetrievalService\|new RankingService\b\| RetrievalService\| RankingService\b" src/main/java`
Expected: no matches in `src/main/java` outside the files being deleted. (`RankingStage` and `ModelRetrievalStage` are the live types.)

If any other `src/main` file references them, STOP and report — Task 5 missed a call site.

- [ ] **Step 2: Delete the files**

```bash
git rm src/main/java/com/recsys/model/service/RetrievalService.java \
       src/test/java/com/recsys/model/service/RetrievalServiceTest.java \
       src/main/java/com/recsys/model/service/RankingService.java \
       src/test/java/com/recsys/model/service/RankingServiceTest.java
```

- [ ] **Step 3: Full build + test suite**

Run: `mvn test`
Expected: BUILD SUCCESS — deletion compiles (nothing referenced the dead services) and all tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: retire dormant RetrievalService and legacy RankingService

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Load-test guard

**Files:** none (verification only).

- [ ] **Step 1: Opt-in load test (8080 recommend path)**

Run: `mvn test -DexcludedGroups="" -Dgroups=load -Dtest=InferenceLoadTest`
Expected: PASS — recall now runs in parallel under a 200 ms per-channel budget; confirm no P95/success-rate regression vs the prior monolithic path.

- [ ] **Step 2: No commit** (verification only).

---

## Self-Review

**Spec coverage:**
- Spec §4.1 `ModelRetrievalStage` → Task 4.
- Spec §4.2 `VocabMembershipEmbeddingStore` → Task 2.
- Spec §4.3 `QuotaPolicy.defaultModelRetrieval()` → Task 1.
- Spec §4.4 `RankingStage` hybrid two-tier → Task 3.
- Spec §4.5 `ModelRuntime` shape → Task 5 Step 1.
- Spec §4.6 `ModelRuntimeProvider` shared stores + per-variant wiring + executor shutdown → Task 5 Step 2.
- Spec §4.7 `RecommendationService` retrieve→rank→hybrid, cold-start via cold retrieval, empty-recall fallback → Task 5 Step 3.
- Spec §4.8 retire `RetrievalService`, demote `CandidateSelectionService` to fallback → Task 5 (fallback) + Task 6 (delete). `RankingService` replaced by `RankingStage` → Task 3 + Task 6.
- Spec §6 error handling (channel timeout/backoff inherited; empty-recall fallback; out-of-vocab tier 2; load-shed unchanged) → Tasks 3, 5.
- Spec §7 testing → Tasks 1–7.

**Placeholder scan:** none — every step has concrete code/commands. The two test reworks (Steps 5–6 of Task 5) reference the existing file's own fixtures (`provider`, `service`, `KNOWN_USER_ID`) which the implementer reads from the current test; this is intentional adaptation, not a placeholder, because the surrounding setup must be preserved.

**Type consistency:**
- `ModelRetrievalStage(MultiChannelRecallService)` / `retrieve(RecommendationQuery,int)` — defined Task 4, used Task 5.
- `RankingStage(UserTowerInferenceService, FeatureEncoder, ModelArtifactService)` / `rank(EncodedFeatures, List<MovieCandidate>, int)` — defined Task 3, used Task 5.
- `VocabMembershipEmbeddingStore(Map<String,Integer>)` implements `EmbeddingStore.getEmbedding(int)` — defined Task 2, used Task 5.
- `QuotaPolicy.defaultModelRetrieval()` — defined Task 1, used Task 5.
- `ModelRuntime.retrievalStage()/rankingStage()` accessors — defined Task 5 Step 1, used Task 5 Step 3.
- `RecommendationQuery(String,int,Set<String>,String)` and `MovieCandidate(String,double,String,Map)` — existing domain records, used Tasks 3–5.
- `FeatureEncoder.EncodedFeatures` (nested, `getUserId()`), `ScoredItem(String,double)` — existing, used Tasks 3, 5.
