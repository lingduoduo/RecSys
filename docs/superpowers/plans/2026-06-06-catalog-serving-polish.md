# Catalog / Recommendation Serving Polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `MultiChannelRecallService` into the recommendation serving path with four concrete recall channels, enable hot-reload of item embeddings into the in-memory vector index, and add a `POST /setuserembedding` endpoint.

**Architecture:** Approach A — thin wrappers over existing `CandidateGenerator` and `DataManager` methods. `ExactVectorIndex` and `EmbeddingLSH` are made mutable (ConcurrentHashMap / CopyOnWriteArrayList) so embedding updates can propagate into the live index without a restart. `RecommendationService` drops its `?mode=` dispatch and delegates entirely to `MultiChannelRecallService`.

**Tech Stack:** Java 21, Armeria (HTTP), Jedis/Redis, JUnit 5, AssertJ, Mockito.

---

## File Map

**New files:**
- `src/main/java/com/recsys/service/retrieval/EmbeddingChannel.java`
- `src/main/java/com/recsys/service/retrieval/TrendingChannel.java`
- `src/main/java/com/recsys/service/retrieval/GenreHistoryChannel.java`
- `src/main/java/com/recsys/service/retrieval/PopularityChannel.java`
- `src/main/java/com/recsys/serving/SetUserEmbeddingService.java`
- `src/test/java/com/recsys/infrastructure/vectordb/EmbeddingLSHTest.java`
- `src/test/java/com/recsys/infrastructure/vectordb/ExactVectorIndexTest.java`
- `src/test/java/com/recsys/service/retrieval/EmbeddingChannelTest.java`
- `src/test/java/com/recsys/service/retrieval/TrendingChannelTest.java`
- `src/test/java/com/recsys/service/retrieval/GenreHistoryChannelTest.java`
- `src/test/java/com/recsys/service/retrieval/PopularityChannelTest.java`

**Modified files:**
- `src/main/java/com/recsys/infrastructure/vectordb/EmbeddingLSH.java`
- `src/main/java/com/recsys/infrastructure/vectordb/ExactVectorIndex.java`
- `src/main/java/com/recsys/infrastructure/vectordb/LshVectorIndex.java`
- `src/main/java/com/recsys/infrastructure/vectordb/VectorIndex.java`
- `src/main/java/com/recsys/infrastructure/vectordb/CandidateGenerator.java`
- `src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java`
- `src/main/java/com/recsys/serving/SetEmbeddingService.java`
- `src/main/java/com/recsys/serving/RecommendationService.java`
- `src/main/java/com/recsys/serving/RecSysServer.java`
- `src/test/java/com/recsys/infrastructure/vectordb/LshVectorIndexTest.java`
- `src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java`
- `src/test/java/com/recsys/serving/RecSysServerIntegrationTest.java`

---

## Task 1: Make EmbeddingLSH mutable — add `add()` method

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/vectordb/EmbeddingLSH.java`
- Create: `src/test/java/com/recsys/infrastructure/vectordb/EmbeddingLSHTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/infrastructure/vectordb/EmbeddingLSHTest.java
package com.recsys.infrastructure.vectordb;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingLSHTest {

    @Test
    void add_newVecAppearsInCandidates() {
        EmbeddingLSH lsh = new EmbeddingLSH(Map.of(1, new float[]{1f, 0f}));
        lsh.add(99, new float[]{1f, 0f});
        assertThat(lsh.candidates(new float[]{1f, 0f})).contains(99);
    }

    @Test
    void add_duplicateIdIsAddedAgainWithoutError() {
        EmbeddingLSH lsh = new EmbeddingLSH(Map.of(1, new float[]{1f, 0f}));
        lsh.add(1, new float[]{0f, 1f}); // should not throw
        assertThat(lsh.candidates(new float[]{0f, 1f})).contains(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -Dtest=EmbeddingLSHTest -pl . -q
```

Expected: compile error — `add(int, float[])` does not exist.

- [ ] **Step 3: Implement — make buckets mutable and add `add()`**

Replace the `buckets` field and `buildBuckets` in `EmbeddingLSH.java`. The field changes from `Map.copyOf` (immutable) to `ConcurrentHashMap` with `CopyOnWriteArrayList` bucket lists so reads during a concurrent `add()` are safe:

```java
// Add imports at the top:
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Change field declaration (line ~21):
private final ConcurrentHashMap<Long, List<Integer>> buckets;

// Replace buildBuckets method:
private ConcurrentHashMap<Long, List<Integer>> buildBuckets(Map<Integer, float[]> embeddings) {
    ConcurrentHashMap<Long, List<Integer>> map = new ConcurrentHashMap<>();
    for (Map.Entry<Integer, float[]> e : embeddings.entrySet()) {
        map.computeIfAbsent(hash(e.getValue()), k -> new CopyOnWriteArrayList<>()).add(e.getKey());
    }
    return map;
}

// Add new method after candidates():
public void add(int id, float[] vec) {
    long h = hash(vec);
    buckets.computeIfAbsent(h, k -> new CopyOnWriteArrayList<>()).add(id);
}
```

Also remove the now-unused `map.replaceAll((k, v) -> List.copyOf(v));` line from the old `buildBuckets`.

- [ ] **Step 4: Run test to verify it passes**

```
mvn test -Dtest=EmbeddingLSHTest -pl . -q
```

Expected: BUILD SUCCESS, 2 tests passed.

- [ ] **Step 5: Confirm existing LSH tests still pass**

```
mvn test -Dtest=LshVectorIndexTest -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/vectordb/EmbeddingLSH.java \
        src/test/java/com/recsys/infrastructure/vectordb/EmbeddingLSHTest.java
git commit -m "feat: make EmbeddingLSH mutable — add add(id, vec) for runtime embedding insertion"
```

---

## Task 2: Make ExactVectorIndex mutable — `addOrUpdate()` + VectorIndex default

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/vectordb/VectorIndex.java`
- Modify: `src/main/java/com/recsys/infrastructure/vectordb/ExactVectorIndex.java`
- Create: `src/test/java/com/recsys/infrastructure/vectordb/ExactVectorIndexTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/infrastructure/vectordb/ExactVectorIndexTest.java
package com.recsys.infrastructure.vectordb;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ExactVectorIndexTest {

    @Test
    void addOrUpdate_newVecIsSearchable() {
        ExactVectorIndex idx = new ExactVectorIndex(Map.of(1, new float[]{0f, 1f}));
        idx.addOrUpdate(2, new float[]{1f, 0f});
        List<SearchResult> results = idx.search(new float[]{1f, 0f}, 2, Set.of());
        assertThat(results).extracting(SearchResult::id).contains(2);
        assertThat(results.get(0).id()).isEqualTo(2); // best match to query [1,0]
    }

    @Test
    void addOrUpdate_updatesExistingVec() {
        ExactVectorIndex idx = new ExactVectorIndex(Map.of(1, new float[]{0f, 1f}));
        idx.addOrUpdate(1, new float[]{1f, 0f}); // now aligned with query [1,0]
        List<SearchResult> results = idx.search(new float[]{1f, 0f}, 1, Set.of());
        assertThat(results.get(0).id()).isEqualTo(1);
        assertThat(results.get(0).score()).isGreaterThan(0.9);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -Dtest=ExactVectorIndexTest -pl . -q
```

Expected: compile error — `addOrUpdate` not defined.

- [ ] **Step 3: Add `addOrUpdate` default to VectorIndex interface**

```java
// src/main/java/com/recsys/infrastructure/vectordb/VectorIndex.java
package com.recsys.infrastructure.vectordb;

import java.util.List;
import java.util.Set;

public interface VectorIndex {
    List<SearchResult> search(float[] query, int k, Set<Integer> excludeIds);

    String name();

    default void addOrUpdate(int id, float[] vec) {}
}
```

- [ ] **Step 4: Make ExactVectorIndex use ConcurrentHashMap and override addOrUpdate**

`ExactVectorIndex` currently stores `Map.copyOf(embeddings)` which is immutable. Change the internal map to `ConcurrentHashMap` and add the override. The full new `ExactVectorIndex.java`:

```java
package com.recsys.infrastructure.vectordb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ExactVectorIndex implements VectorIndex {

    private final ConcurrentHashMap<Integer, float[]> embeddings;

    public ExactVectorIndex(Map<Integer, float[]> embeddings) {
        this.embeddings = new ConcurrentHashMap<>(embeddings);
    }

    @Override
    public List<SearchResult> search(float[] query, int k, Set<Integer> excludeIds) {
        return topK(query, k, excludeIds, embeddings.keySet());
    }

    @Override
    public void addOrUpdate(int id, float[] vec) {
        embeddings.put(id, vec);
    }

    protected List<SearchResult> topK(float[] query, int k, Set<Integer> excludeIds, Iterable<Integer> candidateIds) {
        if (query == null || k <= 0) return List.of();
        Set<Integer> excluded = Objects.requireNonNullElse(excludeIds, Set.of());

        PriorityQueue<SearchResult> best = new PriorityQueue<>(Comparator.comparingDouble(SearchResult::score));

        for (int id : candidateIds) {
            if (excluded.contains(id)) continue;
            float[] candidate = embeddings.get(id);
            if (candidate == null) continue;

            double score = VectorMath.innerProduct(query, candidate);
            if (score == Double.NEGATIVE_INFINITY) continue;

            if (best.size() < k) {
                best.offer(new SearchResult(id, score));
            } else if (score > best.peek().score()) {
                best.poll();
                best.offer(new SearchResult(id, score));
            }
        }

        List<SearchResult> results = new ArrayList<>(best);
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results;
    }

    public static List<SearchResult> search(Map<Integer, float[]> embeddings, float[] query, int k, Set<Integer> excludeIds) {
        if (query == null || k <= 0) return List.of();
        Set<Integer> excluded = Objects.requireNonNullElse(excludeIds, Set.of());

        PriorityQueue<SearchResult> best = new PriorityQueue<>(Comparator.comparingDouble(SearchResult::score));
        for (Map.Entry<Integer, float[]> entry : embeddings.entrySet()) {
            int id = entry.getKey();
            if (excluded.contains(id)) continue;
            double score = VectorMath.innerProduct(query, entry.getValue());
            if (score == Double.NEGATIVE_INFINITY) continue;
            if (best.size() < k) {
                best.offer(new SearchResult(id, score));
            } else if (score > best.peek().score()) {
                best.poll();
                best.offer(new SearchResult(id, score));
            }
        }

        List<SearchResult> results = new ArrayList<>(best);
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results;
    }

    @Override
    public String name() {
        return "exact";
    }
}
```

- [ ] **Step 5: Run tests**

```
mvn test -Dtest=ExactVectorIndexTest,LshVectorIndexTest -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/vectordb/VectorIndex.java \
        src/main/java/com/recsys/infrastructure/vectordb/ExactVectorIndex.java \
        src/test/java/com/recsys/infrastructure/vectordb/ExactVectorIndexTest.java
git commit -m "feat: make ExactVectorIndex mutable via ConcurrentHashMap; add VectorIndex.addOrUpdate default"
```

---

## Task 3: Make LshVectorIndex support `addOrUpdate()`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/vectordb/LshVectorIndex.java`
- Modify: `src/test/java/com/recsys/infrastructure/vectordb/LshVectorIndexTest.java`

- [ ] **Step 1: Write the failing test — append to LshVectorIndexTest**

Add this test to the existing `LshVectorIndexTest` class (inside the class body, after the last existing test):

```java
@Test
void addOrUpdate_newVecIsSearchable() {
    // Start with item 1 at [0,1] (orthogonal to query [1,0]).
    // Add item 2 at [1,0] — should now rank highest.
    LshVectorIndex idx = new LshVectorIndex(Map.of(1, new float[]{0f, 1f}));
    idx.addOrUpdate(2, new float[]{1f, 0f});
    List<SearchResult> results = idx.search(new float[]{1f, 0f}, 2, Set.of());
    assertThat(results).extracting(SearchResult::id).contains(2);
    assertThat(results.get(0).id()).isEqualTo(2);
}
```

Also add the import `import java.util.Map;` if not already present (check the existing imports).

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -Dtest=LshVectorIndexTest#addOrUpdate_newVecIsSearchable -pl . -q
```

Expected: test fails — `addOrUpdate` is a no-op default, so id=2 won't appear.

- [ ] **Step 3: Override `addOrUpdate` in LshVectorIndex**

Replace `LshVectorIndex.java` with:

```java
package com.recsys.infrastructure.vectordb;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class LshVectorIndex extends ExactVectorIndex {

    private final EmbeddingLSH lsh;
    private final Set<Integer> allIds;

    public LshVectorIndex(Map<Integer, float[]> embeddings) {
        super(embeddings);
        this.lsh = new EmbeddingLSH(embeddings);
        this.allIds = ConcurrentHashMap.newKeySet();
        this.allIds.addAll(embeddings.keySet());
    }

    @Override
    public void addOrUpdate(int id, float[] vec) {
        super.addOrUpdate(id, vec);  // update ConcurrentHashMap in ExactVectorIndex
        lsh.add(id, vec);            // update LSH buckets
        allIds.add(id);              // expose id to full-scan fallback
    }

    @Override
    public List<SearchResult> search(float[] query, int k, Set<Integer> excludeIds) {
        Set<Integer> excluded = Objects.requireNonNullElse(excludeIds, Set.of());
        Set<Integer> lshCandidates = lsh.candidates(query);

        if (lshCandidates.size() < k) {
            return topK(query, k, excluded, allIds);
        }

        Set<Integer> candidates = new HashSet<>(lshCandidates);
        candidates.removeAll(excluded);

        if (candidates.size() < k) {
            return topK(query, k, excluded, allIds);
        }

        return topK(query, k, excluded, candidates);
    }

    @Override
    public String name() {
        return "lsh";
    }
}
```

- [ ] **Step 4: Run all vector index tests**

```
mvn test -Dtest=LshVectorIndexTest,ExactVectorIndexTest,EmbeddingLSHTest -pl . -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/vectordb/LshVectorIndex.java \
        src/test/java/com/recsys/infrastructure/vectordb/LshVectorIndexTest.java
git commit -m "feat: LshVectorIndex.addOrUpdate — hot-reload new item vectors into live LSH index"
```

---

## Task 4: Add `CandidateGenerator.updateEmbedding()`

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/vectordb/CandidateGenerator.java`

No new test file needed — this method is a thin delegator tested at the vector-index layer (Tasks 1-3) and at the HTTP layer (Task 13). 

- [ ] **Step 1: Add `updateEmbedding` method to CandidateGenerator**

Add the following method to `CandidateGenerator.java` (e.g., after `byEmbedding`):

```java
public synchronized void updateEmbedding(int id, float[] vec) {
    embeddingIndex.addOrUpdate(id, vec);
}
```

The `synchronized` prevents two concurrent `/setembedding` writes from interleaving their three-step LSH update (super map + lsh bucket + allIds). Concurrent reads via `byEmbedding` are safe because `ConcurrentHashMap` and `CopyOnWriteArrayList` are read-safe.

- [ ] **Step 2: Run existing tests to confirm no regression**

```
mvn test -Dtest=LshVectorIndexTest,ExactVectorIndexTest,RecSysServerIntegrationTest -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/vectordb/CandidateGenerator.java
git commit -m "feat: CandidateGenerator.updateEmbedding — delegate hot-reload to live VectorIndex"
```

---

## Task 5: SetEmbeddingService — call `updateEmbedding` after cache write

**Files:**
- Modify: `src/main/java/com/recsys/serving/SetEmbeddingService.java`

- [ ] **Step 1: Update SetEmbeddingService to accept and call CandidateGenerator**

Replace `SetEmbeddingService.java` with:

```java
package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.vectordb.VectorMath;

import java.util.Map;

public class SetEmbeddingService extends BaseApiService {

    private final EmbeddingStore store;
    private final CandidateGenerator candidateGenerator;

    public SetEmbeddingService(EmbeddingStore store, CandidateGenerator candidateGenerator) {
        this.store = store;
        this.candidateGenerator = candidateGenerator;
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
            try {
                int movieId = requiredIntParam(ctx, "movieId");
                String vecParam = ctx.queryParam("vec");
                String body = (vecParam != null) ? vecParam.trim() : "";
                if (body.isBlank()) body = agg.contentUtf8().trim();
                if (body.isBlank()) return writeError(HttpStatus.BAD_REQUEST, "empty request body");
                float[] vec = VectorMath.parseVector(body);
                long ttl = optionalLongParam(ctx, "ttl", 86400);
                store.setEmbedding(movieId, vec, ttl);
                candidateGenerator.updateEmbedding(movieId, vec);
                return writeJson(HttpStatus.OK,
                        Map.of("ok", true, "movieId", movieId, "dim", vec.length, "ttl", ttl));
            } catch (BadRequestException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (NumberFormatException e) {
                return writeError(HttpStatus.BAD_REQUEST, "invalid vector format: could not parse float");
            } catch (Exception e) {
                log.error("Unexpected error in SetEmbeddingService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
```

- [ ] **Step 2: Fix the compile error in RecSysServerIntegrationTest**

`RecSysServerIntegrationTest` constructs `new SetEmbeddingService(mockEmb)` — update it to pass a mock `CandidateGenerator`:

```java
// In RecSysServerIntegrationTest, update the server configuration block:
CandidateGenerator cg = mock(CandidateGenerator.class);
when(cg.byUserHistory(anyInt(), anyInt())).thenReturn(List.of());
when(cg.byEmbedding(anyInt(), anyInt())).thenReturn(List.of());
when(cg.byGenre(any(), anyInt())).thenReturn(List.of());

// Change:
.service("/setembedding", new SetEmbeddingService(mockEmb))
// To:
.service("/setembedding", new SetEmbeddingService(mockEmb, cg))
```

(The `cg` mock is already defined in the test's `configure(ServerBuilder sb)` block — reuse it.)

- [ ] **Step 3: Run integration test**

```
mvn test -Dtest=RecSysServerIntegrationTest -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/recsys/serving/SetEmbeddingService.java \
        src/test/java/com/recsys/serving/RecSysServerIntegrationTest.java
git commit -m "feat: SetEmbeddingService hot-reloads item embedding into live VectorIndex after cache write"
```

---

## Task 6: SetUserEmbeddingService + register route

**Files:**
- Create: `src/main/java/com/recsys/serving/SetUserEmbeddingService.java`
- Modify: `src/main/java/com/recsys/serving/RecSysServer.java` (route registration only — full wiring in Task 13)
- Modify: `src/test/java/com/recsys/serving/RecSysServerIntegrationTest.java`

- [ ] **Step 1: Write the failing tests — add to RecSysServerIntegrationTest**

Add these two tests inside `RecSysServerIntegrationTest`:

```java
@Test void setUserEmbeddingHappyPath() {
    AggregatedHttpResponse r = server.blockingWebClient().post(
            "/setuserembedding?userId=1", "0.1,0.2,0.3");
    assertThat(r.status()).isEqualTo(HttpStatus.OK);
    assertThat(r.contentUtf8()).contains("\"ok\":true");
    assertThat(r.contentUtf8()).contains("\"userId\":1");
}

@Test void setUserEmbeddingEmptyBody() {
    AggregatedHttpResponse r = server.blockingWebClient().post("/setuserembedding?userId=1", "");
    assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
}
```

Also register the route in the `configure(ServerBuilder sb)` block of the test extension:

```java
EmbeddingStore mockUserEmb = mock(EmbeddingStore.class);
// add to sb:
sb.service("/setuserembedding", new SetUserEmbeddingService(mockUserEmb));
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -Dtest=RecSysServerIntegrationTest#setUserEmbeddingHappyPath -pl . -q
```

Expected: compile error — `SetUserEmbeddingService` does not exist.

- [ ] **Step 3: Create SetUserEmbeddingService**

```java
// src/main/java/com/recsys/serving/SetUserEmbeddingService.java
package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.infrastructure.vectordb.VectorMath;

import java.util.Map;

public class SetUserEmbeddingService extends BaseApiService {

    private final EmbeddingStore store;

    public SetUserEmbeddingService(EmbeddingStore store) {
        this.store = store;
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
            try {
                int userId = requiredIntParam(ctx, "userId");
                String vecParam = ctx.queryParam("vec");
                String body = (vecParam != null) ? vecParam.trim() : "";
                if (body.isBlank()) body = agg.contentUtf8().trim();
                if (body.isBlank()) return writeError(HttpStatus.BAD_REQUEST, "empty request body");
                float[] vec = VectorMath.parseVector(body);
                long ttl = optionalLongParam(ctx, "ttl", 86400);
                store.setEmbedding(userId, vec, ttl);
                return writeJson(HttpStatus.OK,
                        Map.of("ok", true, "userId", userId, "dim", vec.length, "ttl", ttl));
            } catch (BadRequestException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (NumberFormatException e) {
                return writeError(HttpStatus.BAD_REQUEST, "invalid vector format: could not parse float");
            } catch (Exception e) {
                log.error("Unexpected error in SetUserEmbeddingService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
```

- [ ] **Step 4: Run integration tests**

```
mvn test -Dtest=RecSysServerIntegrationTest -pl . -q
```

Expected: BUILD SUCCESS, all tests including the two new ones pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/serving/SetUserEmbeddingService.java \
        src/test/java/com/recsys/serving/RecSysServerIntegrationTest.java
git commit -m "feat: add SetUserEmbeddingService — POST /setuserembedding for runtime user embedding writes"
```

---

## Task 7: EmbeddingChannel

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/EmbeddingChannel.java`
- Create: `src/test/java/com/recsys/service/retrieval/EmbeddingChannelTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/service/retrieval/EmbeddingChannelTest.java
package com.recsys.service.retrieval;

import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.model.Movie;
import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingChannelTest {

    @Test
    void recall_returnsCandidatesForKnownUser() {
        CandidateGenerator cg = mock(CandidateGenerator.class);
        Movie m1 = new Movie(10, "Alpha", 2020, List.of("Action"));
        Movie m2 = new Movie(20, "Beta", 2021, List.of("Drama"));
        when(cg.byEmbedding(42, 10)).thenReturn(List.of(m1, m2));

        EmbeddingChannel channel = new EmbeddingChannel(cg);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("42", 10, Set.of(), null), 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).itemId()).isEqualTo("10");
        assertThat(results.get(1).itemId()).isEqualTo("20");
        assertThat(results.get(0).channel()).isEqualTo("embedding");
        // Scores must be descending (rank-based)
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void recall_emptyForUnknownUser() {
        CandidateGenerator cg = mock(CandidateGenerator.class);
        when(cg.byEmbedding(999, 5)).thenReturn(List.of());

        EmbeddingChannel channel = new EmbeddingChannel(cg);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("999", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }

    @Test
    void name_returnsEmbedding() {
        assertThat(new EmbeddingChannel(mock(CandidateGenerator.class)).name()).isEqualTo("embedding");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -Dtest=EmbeddingChannelTest -pl . -q
```

Expected: compile error — `EmbeddingChannel` does not exist.

- [ ] **Step 3: Implement EmbeddingChannel**

```java
// src/main/java/com/recsys/service/retrieval/EmbeddingChannel.java
package com.recsys.service.retrieval;

import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.model.Movie;
import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EmbeddingChannel implements RecallChannel {

    private final CandidateGenerator candidateGenerator;

    public EmbeddingChannel(CandidateGenerator candidateGenerator) {
        this.candidateGenerator = candidateGenerator;
    }

    @Override
    public String name() {
        return "embedding";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        int userId = Integer.parseInt(query.userId());
        List<Movie> movies = candidateGenerator.byEmbedding(userId, limit);
        List<MovieCandidate> candidates = new ArrayList<>(movies.size());
        // Rank-based score: 1.0 for rank-0, decaying by 1/(rank+1).
        // Preserves relative ordering from the vector index without exposing raw cosine values.
        for (int i = 0; i < movies.size(); i++) {
            double score = 1.0 / (i + 1.0);
            candidates.add(new MovieCandidate(String.valueOf(movies.get(i).id()), score, name(), Map.of()));
        }
        return candidates;
    }
}
```

- [ ] **Step 4: Run test**

```
mvn test -Dtest=EmbeddingChannelTest -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/EmbeddingChannel.java \
        src/test/java/com/recsys/service/retrieval/EmbeddingChannelTest.java
git commit -m "feat: EmbeddingChannel — recall via CandidateGenerator.byEmbedding with rank-based scoring"
```

---

## Task 8: TrendingChannel

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/TrendingChannel.java`
- Create: `src/test/java/com/recsys/service/retrieval/TrendingChannelTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/service/retrieval/TrendingChannelTest.java
package com.recsys.service.retrieval;

import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import com.recsys.streaming.TrendingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrendingChannelTest {

    @Test
    void recall_mapsTopKIdsToMovieCandidates() {
        TrendingStore store = mock(TrendingStore.class);
        when(store.getTopKIds("last_hour", 5)).thenReturn(List.of("10", "20", "30"));

        TrendingChannel channel = new TrendingChannel(store);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).hasSize(3);
        assertThat(results).extracting(MovieCandidate::itemId).containsExactly("10", "20", "30");
        assertThat(results).extracting(MovieCandidate::score).containsOnly(0.6);
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("trending");
    }

    @Test
    void recall_emptyWhenStoreReturnsEmpty() {
        TrendingStore store = mock(TrendingStore.class);
        when(store.getTopKIds("last_hour", 5)).thenReturn(List.of());

        List<MovieCandidate> results = new TrendingChannel(store).recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }

    @Test
    void name_returnsTrending() {
        assertThat(new TrendingChannel(mock(TrendingStore.class)).name()).isEqualTo("trending");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -Dtest=TrendingChannelTest -pl . -q
```

Expected: compile error.

- [ ] **Step 3: Implement TrendingChannel**

```java
// src/main/java/com/recsys/service/retrieval/TrendingChannel.java
package com.recsys.service.retrieval;

import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import com.recsys.streaming.TrendingStore;

import java.util.List;
import java.util.Map;

public class TrendingChannel implements RecallChannel {

    static final double SCORE = 0.6;

    private final TrendingStore trendingStore;

    public TrendingChannel(TrendingStore trendingStore) {
        this.trendingStore = trendingStore;
    }

    @Override
    public String name() {
        return "trending";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        return trendingStore.getTopKIds("last_hour", limit).stream()
                .map(id -> new MovieCandidate(id, SCORE, name(), Map.of()))
                .toList();
    }
}
```

- [ ] **Step 4: Run test**

```
mvn test -Dtest=TrendingChannelTest -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/TrendingChannel.java \
        src/test/java/com/recsys/service/retrieval/TrendingChannelTest.java
git commit -m "feat: TrendingChannel — recall top-K trending items from ShardedTopKStore"
```

---

## Task 9: GenreHistoryChannel

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/GenreHistoryChannel.java`
- Create: `src/test/java/com/recsys/service/retrieval/GenreHistoryChannelTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/service/retrieval/GenreHistoryChannelTest.java
package com.recsys.service.retrieval;

import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.model.Movie;
import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenreHistoryChannelTest {

    @Test
    void recall_returnsGenreBasedCandidates() {
        CandidateGenerator cg = mock(CandidateGenerator.class);
        Movie m = new Movie(5, "Inception", 2010, List.of("Sci-Fi"));
        when(cg.byUserHistory(7, 10)).thenReturn(List.of(m));

        GenreHistoryChannel channel = new GenreHistoryChannel(cg);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("7", 10, Set.of(), null), 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).itemId()).isEqualTo("5");
        assertThat(results.get(0).score()).isEqualTo(0.5);
        assertThat(results.get(0).channel()).isEqualTo("genre_history");
    }

    @Test
    void recall_emptyForUserWithNoHistory() {
        CandidateGenerator cg = mock(CandidateGenerator.class);
        when(cg.byUserHistory(999, 5)).thenReturn(List.of());

        List<MovieCandidate> results = new GenreHistoryChannel(cg).recall(
                new RecommendationQuery("999", 5, Set.of(), null), 5);

        assertThat(results).isEmpty();
    }

    @Test
    void name_returnsGenreHistory() {
        assertThat(new GenreHistoryChannel(mock(CandidateGenerator.class)).name())
                .isEqualTo("genre_history");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -Dtest=GenreHistoryChannelTest -pl . -q
```

Expected: compile error.

- [ ] **Step 3: Implement GenreHistoryChannel**

```java
// src/main/java/com/recsys/service/retrieval/GenreHistoryChannel.java
package com.recsys.service.retrieval;

import com.recsys.infrastructure.vectordb.CandidateGenerator;
import com.recsys.model.Movie;
import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;

import java.util.List;
import java.util.Map;

public class GenreHistoryChannel implements RecallChannel {

    static final double SCORE = 0.5;

    private final CandidateGenerator candidateGenerator;

    public GenreHistoryChannel(CandidateGenerator candidateGenerator) {
        this.candidateGenerator = candidateGenerator;
    }

    @Override
    public String name() {
        return "genre_history";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        int userId = Integer.parseInt(query.userId());
        List<Movie> movies = candidateGenerator.byUserHistory(userId, limit);
        return movies.stream()
                .map(m -> new MovieCandidate(String.valueOf(m.id()), SCORE, name(), Map.of()))
                .toList();
    }
}
```

- [ ] **Step 4: Run test**

```
mvn test -Dtest=GenreHistoryChannelTest -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/GenreHistoryChannel.java \
        src/test/java/com/recsys/service/retrieval/GenreHistoryChannelTest.java
git commit -m "feat: GenreHistoryChannel — recall by genre/history via CandidateGenerator.byUserHistory"
```

---

## Task 10: PopularityChannel

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/PopularityChannel.java`
- Create: `src/test/java/com/recsys/service/retrieval/PopularityChannelTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/service/retrieval/PopularityChannelTest.java
package com.recsys.service.retrieval;

import com.recsys.infrastructure.DataManager;
import com.recsys.model.Movie;
import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PopularityChannelTest {

    @Test
    void recall_combinesTopRatedAndLatest() {
        DataManager dm = mock(DataManager.class);
        Movie a = new Movie(1, "Top Rated", 2020, List.of("Action"));
        Movie b = new Movie(2, "Latest", 2023, List.of("Drama"));
        when(dm.getTopRatedMovies(anyInt())).thenReturn(List.of(a));
        when(dm.getLatestMovies(anyInt())).thenReturn(List.of(b));

        PopularityChannel channel = new PopularityChannel(dm);
        List<MovieCandidate> results = channel.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).extracting(MovieCandidate::itemId).containsExactlyInAnyOrder("1", "2");
        assertThat(results).extracting(MovieCandidate::score).containsOnly(0.4);
        assertThat(results).extracting(MovieCandidate::channel).containsOnly("popularity");
    }

    @Test
    void recall_deduplicatesOverlappingTopRatedAndLatest() {
        DataManager dm = mock(DataManager.class);
        Movie m = new Movie(1, "Both", 2022, List.of("Comedy"));
        when(dm.getTopRatedMovies(anyInt())).thenReturn(List.of(m));
        when(dm.getLatestMovies(anyInt())).thenReturn(List.of(m)); // same movie in both

        List<MovieCandidate> results = new PopularityChannel(dm).recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).itemId()).isEqualTo("1");
    }

    @Test
    void name_returnsPopularity() {
        assertThat(new PopularityChannel(mock(DataManager.class)).name()).isEqualTo("popularity");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -Dtest=PopularityChannelTest -pl . -q
```

Expected: compile error.

- [ ] **Step 3: Implement PopularityChannel**

```java
// src/main/java/com/recsys/service/retrieval/PopularityChannel.java
package com.recsys.service.retrieval;

import com.recsys.infrastructure.DataManager;
import com.recsys.model.Movie;
import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PopularityChannel implements RecallChannel {

    static final double SCORE = 0.4;

    private final DataManager dataManager;

    public PopularityChannel(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public String name() {
        return "popularity";
    }

    @Override
    public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
        Map<Integer, Movie> deduped = new LinkedHashMap<>();
        for (Movie m : dataManager.getTopRatedMovies(limit)) deduped.put(m.id(), m);
        for (Movie m : dataManager.getLatestMovies(limit)) deduped.put(m.id(), m);
        return deduped.values().stream()
                .map(m -> new MovieCandidate(String.valueOf(m.id()), SCORE, name(), Map.of()))
                .toList();
    }
}
```

- [ ] **Step 4: Run test**

```
mvn test -Dtest=PopularityChannelTest -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/PopularityChannel.java \
        src/test/java/com/recsys/service/retrieval/PopularityChannelTest.java
git commit -m "feat: PopularityChannel — cold-start fallback via top-rated + latest movies"
```

---

## Task 11: MultiChannelRecallService — channel failure resilience

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java`
- Modify: `src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java`

- [ ] **Step 1: Write the failing test — add to MultiChannelRecallServiceTest**

Add this test inside `MultiChannelRecallServiceTest`:

```java
@Test
void failingChannelIsSkipped_othersStillContribute() {
    RecallChannel broken = new RecallChannel() {
        @Override public String name() { return "broken"; }
        @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
            throw new RuntimeException("Redis down");
        }
    };
    RecallChannel good = channel("good",
            new MovieCandidate("42", 0.8, "good", Map.of()));

    MultiChannelRecallService service = new MultiChannelRecallService(List.of(broken, good));
    List<MovieCandidate> recalled = service.recall(
            new RecommendationQuery("u1", 10, Set.of(), null), 10);

    assertThat(recalled).hasSize(1);
    assertThat(recalled.get(0).itemId()).isEqualTo("42");
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -Dtest=MultiChannelRecallServiceTest#failingChannelIsSkipped_othersStillContribute -pl . -q
```

Expected: test fails with `RuntimeException: Redis down` propagating up.

- [ ] **Step 3: Wrap each channel call in try/catch in MultiChannelRecallService**

Replace the for-loop in `MultiChannelRecallService.recall()`:

```java
// Add import: import org.slf4j.Logger; import org.slf4j.LoggerFactory;
private static final Logger log = LoggerFactory.getLogger(MultiChannelRecallService.class);

// In recall(), replace:
//   List<MovieCandidate> recalled = channel.recall(query, limit);
// With:
for (RecallChannel channel : channels) {
    List<MovieCandidate> recalled;
    try {
        recalled = channel.recall(query, limit);
    } catch (Exception e) {
        log.warn("Channel '{}' failed — skipping: {}", channel.name(), e.getMessage());
        continue;
    }
    if (recalled == null) continue;
    for (MovieCandidate candidate : recalled) {
        if (query.excludedItemIds().contains(candidate.itemId())) continue;
        merged.merge(candidate.itemId(), candidate,
                (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
    }
}
```

The complete new `recall` method body:

```java
public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
    Objects.requireNonNull(query, "query");
    if (limit <= 0) {
        return List.of();
    }

    Map<String, MovieCandidate> merged = new LinkedHashMap<>();
    for (RecallChannel channel : channels) {
        List<MovieCandidate> recalled;
        try {
            recalled = channel.recall(query, limit);
        } catch (Exception e) {
            log.warn("Channel '{}' failed — skipping: {}", channel.name(), e.getMessage());
            continue;
        }
        if (recalled == null) continue;
        for (MovieCandidate candidate : recalled) {
            if (query.excludedItemIds().contains(candidate.itemId())) continue;
            merged.merge(candidate.itemId(), candidate,
                    (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
        }
    }

    return merged.values().stream()
            .sorted(Comparator.comparingDouble(MovieCandidate::score).reversed()
                    .thenComparing(MovieCandidate::itemId))
            .limit(limit)
            .toList();
}
```

- [ ] **Step 4: Run all MultiChannelRecallService tests**

```
mvn test -Dtest=MultiChannelRecallServiceTest -pl . -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/MultiChannelRecallService.java \
        src/test/java/com/recsys/service/retrieval/MultiChannelRecallServiceTest.java
git commit -m "feat: MultiChannelRecallService — catch channel exceptions so one failure doesn't abort the pipeline"
```

---

## Task 12: RecommendationService — replace mode dispatch with unified pipeline

**Files:**
- Modify: `src/main/java/com/recsys/serving/RecommendationService.java`

- [ ] **Step 1: Rewrite RecommendationService to use MultiChannelRecallService**

`RecommendationQuery.limit` is capped at 100. Lower the HTTP `k` max from 200 to 100 to stay consistent. The over-fetch multiplier of 3 is applied to the channel recall limit, not to the query itself.

Replace the entire file:

```java
package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.DataManager;
import com.recsys.model.Movie;
import com.recsys.model.MovieCandidate;
import com.recsys.model.RecommendationQuery;
import com.recsys.model.RecommendationResponse;
import com.recsys.model.User;
import com.recsys.service.retrieval.MultiChannelRecallService;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class RecommendationService extends BaseApiService {

    private static final int RECALL_MULTIPLIER = 3;

    private final DataManager dataManager;
    private final MultiChannelRecallService recallService;

    public RecommendationService(DataManager dataManager, MultiChannelRecallService recallService) {
        this.dataManager = dataManager;
        this.recallService = recallService;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                int userId = requiredIntParam(ctx, "userId");
                User user = dataManager.getUserById(userId);
                if (user == null) return writeError(HttpStatus.NOT_FOUND, "user not found", "userId", userId);

                int k = optionalIntParam(ctx, "k", 20, 1, 100);
                Set<String> excludedItemIds = dataManager.getWatchedMovieIds(userId).stream()
                        .map(String::valueOf)
                        .collect(Collectors.toSet());
                RecommendationQuery query = new RecommendationQuery(
                        String.valueOf(userId), k, excludedItemIds, null);

                List<MovieCandidate> candidates = recallService.recall(query, k * RECALL_MULTIPLIER);
                List<Movie> movies = candidates.stream()
                        .map(c -> {
                            try { return dataManager.getMovieById(Integer.parseInt(c.itemId())); }
                            catch (NumberFormatException e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .toList();

                return writeJson(HttpStatus.OK, new RecommendationResponse(user, movies));

            } catch (BadRequestException | IllegalArgumentException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in RecommendationService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
```

- [ ] **Step 2: Update RecSysServerIntegrationTest to use new constructor**

The test's `ServerExtension.configure` block constructs `RecommendationService` with the old signature `(dataManager, cg, topkStore)`. Update it to the new signature using a stubbed `MultiChannelRecallService`:

```java
// In the configure(ServerBuilder sb) block, replace:
//   RecommendationService rec = new RecommendationService(mockData, cg, mockTopk);
// With:
MultiChannelRecallService recallService = mock(MultiChannelRecallService.class);
when(recallService.recall(any(), anyInt())).thenReturn(List.of());
RecommendationService rec = new RecommendationService(mockData, recallService);
```

Add the import `import com.recsys.service.retrieval.MultiChannelRecallService;` at the top. Remove the unused `TrendingStore mockTopk` static field if it's now only used for this (check — it may still be used elsewhere in the test file; keep it if so).

- [ ] **Step 3: Compile check**

```
mvn test-compile -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Run the integration test**

```
mvn test -Dtest=RecSysServerIntegrationTest -pl . -q
```

Expected: BUILD SUCCESS. All existing tests pass (the mocked `recallService` returns empty, so recommendation results are empty — same as before for the stub).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/serving/RecommendationService.java \
        src/test/java/com/recsys/serving/RecSysServerIntegrationTest.java
git commit -m "feat: RecommendationService — replace mode= dispatch with unified MultiChannelRecallService pipeline

Removes ?mode=topk, ?mode=trending, ?mode=embedding — all callers now receive blended results.
HTTP k max lowered from 200 to 100 to match RecommendationQuery.limit cap."
```

---

## Task 13: RecSysServer — wire all four channels + `/setuserembedding`

**Files:**
- Modify: `src/main/java/com/recsys/serving/RecSysServer.java`

- [ ] **Step 1: Update RecSysServer.run() to build channels and MultiChannelRecallService**

Replace the `run()` method body in `RecSysServer.java`. Key changes:
1. Build all four channels from existing dependencies.
2. Build `MultiChannelRecallService` from the channel list.
3. Pass `candidateGenerator` to `SetEmbeddingService`.
4. Pass `userEmbCache` to `SetUserEmbeddingService`.
5. Register `/setuserembedding` route.

New constants to add at the top of the class:
```java
private static final String ROUTE_SET_USER_EMBEDDING = "/setuserembedding";
```

New `run()` method:

```java
public void run() throws Exception {
    int port = readIntEnv("PORT", DEFAULT_PORT);
    Pool<Jedis> jedisPool = RedisConnectionFactory.fromEnv();
    try {
        DataManager dataManager = DataManager.getInstance();
        PairPredictionService pairPredictionService = new PairPredictionService();
        RedisEmbeddingStore embStore     = new RedisEmbeddingStore(jedisPool, "i2vEmb");
        RedisEmbeddingStore userEmbStore = new RedisEmbeddingStore(jedisPool, "u2vEmb");
        TrendingStore topkStore = new ShardedTopKStore(jedisPool, "topk:");

        seedEmbeddings(embStore, userEmbStore);

        LocalEmbeddingCache embCache = new LocalEmbeddingCache(embStore);
        embCache.preload(DataLoader.loadMovieEmbeddings());
        embCache.warmUp();

        LocalEmbeddingCache userEmbCache = new LocalEmbeddingCache(userEmbStore);
        userEmbCache.preload(DataLoader.loadUserEmbeddings());
        userEmbCache.warmUp();

        CandidateGenerator candidateGenerator = new CandidateGenerator(dataManager, userEmbCache);

        MultiChannelRecallService recallService = new MultiChannelRecallService(List.of(
                new EmbeddingChannel(candidateGenerator),
                new TrendingChannel(topkStore),
                new GenreHistoryChannel(candidateGenerator),
                new PopularityChannel(dataManager)
        ));

        MovieService movieService = new MovieService(dataManager);
        UserService userService = new UserService(dataManager);
        RecommendationService recommendationService =
                new RecommendationService(dataManager, recallService);

        String corsOrigin = System.getenv("CORS_ALLOWED_ORIGIN");

        ServerBuilder sb = Server.builder()
                .http(port)
                .service(ROUTE_ITEM, movieService)
                .service(ROUTE_ITEM_ALIAS, movieService)
                .service(ROUTE_USER, userService)
                .service(ROUTE_USER_ALIAS, userService)
                .service(ROUTE_SIMILAR, new SimilarMovieService(embCache))
                .service(ROUTE_RECOMMENDATION, recommendationService)
                .service(ROUTE_RECOMMENDATION_ALIAS, recommendationService)
                .service(ROUTE_SET_EMBEDDING, new SetEmbeddingService(embCache, candidateGenerator))
                .service(ROUTE_SET_USER_EMBEDDING, new SetUserEmbeddingService(userEmbCache))
                .service(ROUTE_HEALTH, new HealthService())
                .service(Route.builder()
                                 .regex("^" + ROUTE_PREDICT + "$")
                                 .methods(HttpMethod.POST)
                                 .build(),
                         new PredictionService(pairPredictionService));

        if (corsOrigin != null && !corsOrigin.isBlank()) {
            sb.decorator(CorsService.builder(corsOrigin)
                    .allowAllRequestHeaders(true)
                    .allowRequestMethods(
                            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                            HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.HEAD)
                    .newDecorator());
        }

        Server server = sb.build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop().join();
            jedisPool.close();
        }, "recsys-shutdown"));
        log.info("Starting RecSys serving API on port {}", port);
        server.start().join();
        server.blockUntilShutdown();
    } catch (Exception e) {
        jedisPool.close();
        throw e;
    }
}
```

Add new imports needed:
```java
import com.recsys.service.retrieval.EmbeddingChannel;
import com.recsys.service.retrieval.GenreHistoryChannel;
import com.recsys.service.retrieval.MultiChannelRecallService;
import com.recsys.service.retrieval.PopularityChannel;
import com.recsys.service.retrieval.TrendingChannel;
import java.util.List;
```

Remove the now-unused import `import com.recsys.streaming.TrendingStore;` — it is still used implicitly as the type of `topkStore` so keep it. Check and keep only what compiles.

- [ ] **Step 2: Compile check**

```
mvn test-compile -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run full test suite**

```
mvn test -pl . -q
```

Expected: BUILD SUCCESS. All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/recsys/serving/RecSysServer.java
git commit -m "feat: wire MultiChannelRecallService pipeline in RecSysServer — 4 channels + /setuserembedding"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Covered by |
|---|---|
| Replace `?mode=` dispatch with unified pipeline | Task 12 |
| 4 concrete RecallChannel implementations | Tasks 7–10 |
| Hot-reload item embedding into VectorIndex on `/setembedding` write | Tasks 1–5 |
| `POST /setuserembedding` endpoint | Task 6 |
| Channel failure resilience (try/catch) | Task 11 |
| Lower HTTP k max to 100 | Task 12 |
| `VectorIndex.addOrUpdate` default no-op | Task 2 |
| `EmbeddingLSH.add()` mutable buckets | Task 1 |
| `ExactVectorIndex` ConcurrentHashMap | Task 2 |
| `LshVectorIndex` ConcurrentHashMap.newKeySet() + override | Task 3 |
| `CandidateGenerator.updateEmbedding()` | Task 4 |
| `SetEmbeddingService` inject CandidateGenerator | Task 5 |
| Wire everything in `RecSysServer` | Task 13 |
| `/setuserembedding` integration tests | Task 6 |
| Channel failure integration test in MultiChannelRecallServiceTest | Task 11 |

All spec requirements covered. ✓
