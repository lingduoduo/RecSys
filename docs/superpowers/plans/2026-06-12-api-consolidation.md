# API & Service Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a `RecommendationPipeline` interface with four named implementations, expose a unified `POST /v2/recommend` endpoint on all three backends, fix the gateway route table, and rename the knowledge base package.

**Architecture:** A single `RecommendationPipeline` interface (in `service/recommendation/`) is the contract for all four recommendation paths. Each backend adds a new `/v2/recommend` endpoint that delegates to its pipeline implementation while leaving all existing endpoints untouched. The API gateway route table is repaired (4 dead routes removed, 5 new production routes added).

**Tech Stack:** Java 21, Armeria (`:6010`, `:7010`), Spring Boot 3 + MockMvc (`:8080`), JUnit 5 + AssertJ + Mockito, `armeria-junit5` `ServerExtension` for Armeria integration tests, Jackson (records supported natively in 2.12+).

**Spec:** `docs/superpowers/specs/2026-06-12-api-consolidation-design.md`

---

## File Map

**New source files:**
- `src/main/java/com/recsys/service/recommendation/RecommendationPipeline.java`
- `src/main/java/com/recsys/service/recommendation/SequentialRecommendationPipeline.java`
- `src/main/java/com/recsys/model/service/OnnxInferencePipeline.java`
- `src/main/java/com/recsys/online/serving/OnlineBlendingPipeline.java`
- `src/main/java/com/recsys/serving/RecommendV2Service.java`
- `src/main/java/com/recsys/model/controller/RecommendationV2Controller.java`
- `src/main/java/com/recsys/online/serving/OnlineRecommendV2Service.java`

**Modified source files:**
- `src/main/java/com/recsys/service/recommendation/RecommendationOrchestrator.java` — add `implements RecommendationPipeline`
- `src/main/java/com/recsys/serving/RecSysServer.java` — register `/v2/recommend`
- `src/main/java/com/recsys/online/serving/OnlinePredictionServer.java` — register `/v2/recommend`
- `src/main/java/com/recsys/microservice/MicroserviceRoute.java` — route table overhaul
- `src/main/java/com/recsys/config/GlobalExceptionHandler.java` — add 501 handler

**Knowledge base package rename (no logic changes):**
- `src/main/java/com/recsys/model/knowledge/KnowledgeBaseController.java`
- `src/main/java/com/recsys/model/knowledge/KnowledgeBaseFacadeService.java`
- `src/main/java/com/recsys/model/knowledge/KnowledgeBaseConverter.java`
- `src/main/java/com/recsys/model/knowledge/KnowledgeBaseDTO.java`
- `src/main/java/com/recsys/model/knowledge/KnowledgeBase.java`
- `src/main/java/com/recsys/model/knowledge/CreateKnowledgeBaseRequest.java`
- `src/main/java/com/recsys/model/knowledge/UpdateKnowledgeBaseRequest.java`
- `src/main/java/com/recsys/model/knowledge/CreateKnowledgeBaseResponse.java`
- `src/main/java/com/recsys/model/knowledge/GetKnowledgeBasesResponse.java`
- `src/main/java/com/recsys/model/knowledge/KnowledgeBaseVO.java`

**New test files:**
- `src/test/java/com/recsys/service/recommendation/RecommendationPipelineTest.java`
- `src/test/java/com/recsys/service/recommendation/SequentialRecommendationPipelineTest.java`
- `src/test/java/com/recsys/model/service/OnnxInferencePipelineTest.java`
- `src/test/java/com/recsys/online/serving/OnlineBlendingPipelineTest.java`
- `src/test/java/com/recsys/serving/RecSysV2RecommendIntegrationTest.java`
- `src/test/java/com/recsys/serving/RecSysServerRegressionTest.java`
- `src/test/java/com/recsys/model/controller/ModelV2RecommendIntegrationTest.java`
- `src/test/java/com/recsys/model/controller/SequentialStubIntegrationTest.java`
- `src/test/java/com/recsys/model/controller/RecommendationControllerRegressionTest.java`
- `src/test/java/com/recsys/online/serving/OnlineV2RecommendIntegrationTest.java`
- `src/test/java/com/recsys/online/serving/OnlinePredictionRegressionTest.java`
- `src/test/java/com/recsys/microservice/GatewayRouteTableTest.java` — extend existing
- `src/test/java/com/recsys/service/recommendation/CrossPathConsistencyTest.java`
- `src/test/java/com/recsys/serving/EmbeddingRecallLoadTest.java`

---

## Task 1: RecommendationPipeline Interface

**Files:**
- Create: `src/main/java/com/recsys/service/recommendation/RecommendationPipeline.java`
- Modify: `src/main/java/com/recsys/service/recommendation/RecommendationOrchestrator.java`
- Test: `src/test/java/com/recsys/service/recommendation/RecommendationPipelineTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/service/recommendation/RecommendationPipelineTest.java
package com.recsys.service.recommendation;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.service.hydrator.RecommendationHydrator;
import com.recsys.service.pagination.CursorPaginationService;
import com.recsys.service.pagination.Page;
import com.recsys.service.ranking.CandidateRanker;
import com.recsys.service.retrieval.MultiChannelRecallService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationPipelineTest {

    @Test
    void orchestratorImplementsPipelineInterface() {
        MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
        CandidateRanker ranker = mock(CandidateRanker.class);
        CursorPaginationService pagination = mock(CursorPaginationService.class);

        RankedMovie movie = new RankedMovie("42", 0.9, 1, Map.of());
        when(recall.recall(any(), anyInt())).thenReturn(List.of(mock(MovieCandidate.class)));
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of(movie));
        when(pagination.page(any(), any(), anyInt()))
                .thenReturn(new Page<>(List.of(movie), null));

        RecommendationPipeline pipeline =
                new RecommendationOrchestrator(recall, ranker, RecommendationHydrator.IDENTITY, pagination);

        RecommendationResult result = pipeline.recommend(
                new RecommendationQuery("u1", 5, Set.of(), null));

        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo("u1");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).itemId()).isEqualTo("42");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=RecommendationPipelineTest -DskipTests=false
```

Expected: FAIL — `RecommendationPipeline` does not exist yet.

- [ ] **Step 3: Create the interface**

```java
// src/main/java/com/recsys/service/recommendation/RecommendationPipeline.java
package com.recsys.service.recommendation;

import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;

public interface RecommendationPipeline {
    RecommendationResult recommend(RecommendationQuery query);
}
```

- [ ] **Step 4: Add `implements RecommendationPipeline` to `RecommendationOrchestrator`**

Open `src/main/java/com/recsys/service/recommendation/RecommendationOrchestrator.java`.
Change line 17:
```java
// before
public class RecommendationOrchestrator {
// after
public class RecommendationOrchestrator implements RecommendationPipeline {
```

No other changes — `recommend(RecommendationQuery query)` already matches the interface signature.

- [ ] **Step 5: Run tests to verify they pass**

```bash
mvn test -Dtest=RecommendationPipelineTest,RecommendationOrchestratorTest -DskipTests=false
```

Expected: PASS for both.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/service/recommendation/RecommendationPipeline.java \
        src/main/java/com/recsys/service/recommendation/RecommendationOrchestrator.java \
        src/test/java/com/recsys/service/recommendation/RecommendationPipelineTest.java
git commit -m "feat: extract RecommendationPipeline interface; RecommendationOrchestrator implements it"
```

---

## Task 2: SequentialRecommendationPipeline Stub + 501 Handler

**Files:**
- Create: `src/main/java/com/recsys/service/recommendation/SequentialRecommendationPipeline.java`
- Modify: `src/main/java/com/recsys/config/GlobalExceptionHandler.java`
- Test: `src/test/java/com/recsys/service/recommendation/SequentialRecommendationPipelineTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/service/recommendation/SequentialRecommendationPipelineTest.java
package com.recsys.service.recommendation;

import com.recsys.domain.RecommendationQuery;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SequentialRecommendationPipelineTest {

    @Test
    void recommend_throwsUnsupportedOperationException() {
        RecommendationPipeline pipeline = new SequentialRecommendationPipeline();
        assertThatThrownBy(() -> pipeline.recommend(
                new RecommendationQuery("u1", 5, Set.of(), null)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not yet implemented");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=SequentialRecommendationPipelineTest -DskipTests=false
```

Expected: FAIL — class does not exist.

- [ ] **Step 3: Create the stub**

```java
// src/main/java/com/recsys/service/recommendation/SequentialRecommendationPipeline.java
package com.recsys.service.recommendation;

import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;

public final class SequentialRecommendationPipeline implements RecommendationPipeline {

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        throw new UnsupportedOperationException(
                "Sequential/LLM recommendation is not yet implemented. " +
                "Future: SASRec / BERT4Rec / LLM-based path.");
    }
}
```

- [ ] **Step 4: Add 501 handler to `GlobalExceptionHandler`**

Open `src/main/java/com/recsys/config/GlobalExceptionHandler.java`.
Add this method before the catch-all `handleUnexpected`:

```java
@ExceptionHandler(UnsupportedOperationException.class)
@ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
public ApiError handleNotImplemented(UnsupportedOperationException ex) {
    return new ApiError(ex.getMessage(), List.of());
}
```

Also add the import if not already present (it's in `org.springframework.http.HttpStatus` which is already imported).

- [ ] **Step 5: Run tests to verify they pass**

```bash
mvn test -Dtest=SequentialRecommendationPipelineTest -DskipTests=false
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/service/recommendation/SequentialRecommendationPipeline.java \
        src/main/java/com/recsys/config/GlobalExceptionHandler.java \
        src/test/java/com/recsys/service/recommendation/SequentialRecommendationPipelineTest.java
git commit -m "feat: add SequentialRecommendationPipeline stub (501) and 501 handler in GlobalExceptionHandler"
```

---

## Task 3: OnnxInferencePipeline

**Files:**
- Create: `src/main/java/com/recsys/model/service/OnnxInferencePipeline.java`
- Test: `src/test/java/com/recsys/model/service/OnnxInferencePipelineTest.java`

**Key type mappings:**
- `RecommendationQuery.userId()` (String) → `RecommendRequest.setUserId(String)`
- `RecommendationQuery.limit()` (int) → `RecommendRequest.setK(int)`
- `RecommendationQuery.excludedItemIds()` (Set<String>) → `RecommendRequest.setExcludeItemIds(List<String>)`
- `RecommendResponse.recommendations()` (List<ScoredItem>) → `List<RankedMovie>` — use `ScoredItem.itemId()`, `ScoredItem.score()`, rank = position + 1
- `RecommendResponse.abTestVariant()` + `RecommendResponse.modelVersion()` → `RecommendationResult.trace()`
- `nextCursor` = null (ONNX path does not paginate)

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/model/service/OnnxInferencePipelineTest.java
package com.recsys.model.service;

import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.model.dto.ScoredItem;
import com.recsys.model.request.RecommendRequest;
import com.recsys.model.response.RecommendResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OnnxInferencePipelineTest {

    private final RecommendationService service = mock(RecommendationService.class);
    private final ABTestService abTest = mock(ABTestService.class);
    private final OnnxInferencePipeline pipeline = new OnnxInferencePipeline(service, abTest);

    @Test
    void convertsQueryToRequestAndMapsResponse() {
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("u1", "training", 0);
        when(abTest.getAssignmentForUser("u1")).thenReturn(assignment);
        when(service.recommend(any(RecommendRequest.class), any())).thenReturn(
                new RecommendResponse("u1", "v1.0", "training",
                        List.of(new ScoredItem("42", 0.95), new ScoredItem("7", 0.80))));

        RecommendationResult result = pipeline.recommend(
                new RecommendationQuery("u1", 5, Set.of(), null));

        assertThat(result.userId()).isEqualTo("u1");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).itemId()).isEqualTo("42");
        assertThat(result.items().get(0).score()).isEqualTo(0.95);
        assertThat(result.items().get(0).rank()).isEqualTo(1);
        assertThat(result.items().get(1).rank()).isEqualTo(2);
        assertThat(result.nextCursor()).isNull();
        assertThat(result.trace()).containsEntry("abTestVariant", "training");
        assertThat(result.trace()).containsEntry("modelVersion", "v1.0");
    }

    @Test
    void forwardsExcludedItemIds() {
        ABTestService.Assignment assignment =
                new ABTestService.Assignment("u2", "training", 0);
        when(abTest.getAssignmentForUser("u2")).thenReturn(assignment);
        when(service.recommend(any(RecommendRequest.class), any())).thenReturn(
                new RecommendResponse("u2", "v1.0", "training", List.of()));

        pipeline.recommend(new RecommendationQuery("u2", 10, Set.of("1", "2"), null));

        verify(service).recommend(argThat(req ->
                req.getExcludeItemIds().contains("1") &&
                req.getExcludeItemIds().contains("2")), any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=OnnxInferencePipelineTest -DskipTests=false
```

Expected: FAIL — class does not exist.

- [ ] **Step 3: Create `OnnxInferencePipeline`**

```java
// src/main/java/com/recsys/model/service/OnnxInferencePipeline.java
package com.recsys.model.service;

import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.model.dto.ScoredItem;
import com.recsys.model.request.RecommendRequest;
import com.recsys.model.response.RecommendResponse;
import com.recsys.service.recommendation.RecommendationPipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OnnxInferencePipeline implements RecommendationPipeline {

    private final RecommendationService recommendationService;
    private final ABTestService abTestService;

    public OnnxInferencePipeline(RecommendationService recommendationService,
                                  ABTestService abTestService) {
        this.recommendationService = recommendationService;
        this.abTestService = abTestService;
    }

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        ABTestService.Assignment assignment =
                abTestService.getAssignmentForUser(query.userId());
        RecommendRequest request = toRequest(query);
        RecommendResponse response = recommendationService.recommend(request, assignment);
        return toResult(query.userId(), response);
    }

    private static RecommendRequest toRequest(RecommendationQuery query) {
        RecommendRequest request = new RecommendRequest();
        request.setUserId(query.userId());
        request.setK(query.limit());
        if (!query.excludedItemIds().isEmpty()) {
            request.setExcludeItemIds(new ArrayList<>(query.excludedItemIds()));
        }
        return request;
    }

    private static RecommendationResult toResult(String userId, RecommendResponse response) {
        List<ScoredItem> raw = response.recommendations();
        List<RankedMovie> items = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            ScoredItem item = raw.get(i);
            items.add(new RankedMovie(item.itemId(), item.score(), i + 1, Map.of()));
        }
        Map<String, String> trace = Map.of(
                "abTestVariant", response.abTestVariant() != null ? response.abTestVariant() : "",
                "modelVersion",  response.modelVersion()  != null ? response.modelVersion()  : "");
        return new RecommendationResult(userId, items, null, trace);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=OnnxInferencePipelineTest -DskipTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/model/service/OnnxInferencePipeline.java \
        src/test/java/com/recsys/model/service/OnnxInferencePipelineTest.java
git commit -m "feat: add OnnxInferencePipeline adapter (RecommendationQuery → ONNX → RecommendationResult)"
```

---

## Task 4: OnlineBlendingPipeline

**Files:**
- Create: `src/main/java/com/recsys/online/serving/OnlineBlendingPipeline.java`
- Test: `src/test/java/com/recsys/online/serving/OnlineBlendingPipelineTest.java`

**Key type mappings:**
- `RecommendationQuery.userId()` (String) → `Integer.parseInt(userId)` for `OnlineRecommendationRequest(int, String, int)`
- `OnlineRecommendationResult.recommendations()` (List<Movie>) → `List<RankedMovie>` — score = `(n - i) / n`, rank = `i + 1`, itemId = `String.valueOf(movie.id())`
- `OnlineRecommendationResult.strategy()` + `.window()` → `RecommendationResult.trace()`
- `nextCursor` = null (online path does not paginate)

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/recsys/online/serving/OnlineBlendingPipelineTest.java
package com.recsys.online.serving;

import com.recsys.domain.Movie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.domain.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineBlendingPipelineTest {

    private final OnlineRecommendationService service = mock(OnlineRecommendationService.class);
    private final OnlineBlendingPipeline pipeline = new OnlineBlendingPipeline(service);

    @Test
    void convertsMoviesToRankedMoviesWithPositionScores() {
        User user = new User(1, "Alice");
        List<Movie> recs = List.of(
                new Movie(10, "A", 2020, List.of()),
                new Movie(20, "B", 2021, List.of()));
        when(service.recommend(any())).thenReturn(
                new OnlineRecommendationResult(user, "last_hour", "online+model",
                        List.of(), List.of(), recs));

        RecommendationResult result = pipeline.recommend(
                new RecommendationQuery("1", 5, Set.of(), null));

        assertThat(result.userId()).isEqualTo("1");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).itemId()).isEqualTo("10");
        assertThat(result.items().get(0).rank()).isEqualTo(1);
        assertThat(result.items().get(0).score()).isGreaterThan(result.items().get(1).score());
        assertThat(result.items().get(1).itemId()).isEqualTo("20");
        assertThat(result.items().get(1).rank()).isEqualTo(2);
        assertThat(result.nextCursor()).isNull();
        assertThat(result.trace()).containsEntry("strategy", "online+model");
        assertThat(result.trace()).containsEntry("window", "last_hour");
    }

    @Test
    void nonNumericUserIdThrowsIllegalArgument() {
        assertThatThrownBy(() -> pipeline.recommend(
                new RecommendationQuery("not-a-number", 5, Set.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId must be numeric");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=OnlineBlendingPipelineTest -DskipTests=false
```

Expected: FAIL — class does not exist.

- [ ] **Step 3: Create `OnlineBlendingPipeline`**

```java
// src/main/java/com/recsys/online/serving/OnlineBlendingPipeline.java
package com.recsys.online.serving;

import com.recsys.domain.Movie;
import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.service.recommendation.RecommendationPipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OnlineBlendingPipeline implements RecommendationPipeline {

    private final OnlineRecommendationService recommendationService;

    public OnlineBlendingPipeline(OnlineRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Override
    public RecommendationResult recommend(RecommendationQuery query) {
        int userId;
        try {
            userId = Integer.parseInt(query.userId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "userId must be numeric for online path: " + query.userId());
        }
        OnlineRecommendationResult online = recommendationService.recommend(
                new OnlineRecommendationRequest(userId, null, query.limit()));
        Map<String, String> trace = Map.of(
                "strategy", online.strategy() != null ? online.strategy() : "online",
                "window",   online.window()   != null ? online.window()   : "");
        return new RecommendationResult(
                query.userId(), toRanked(online.recommendations()), null, trace);
    }

    private static List<RankedMovie> toRanked(List<Movie> movies) {
        int n = movies.size();
        List<RankedMovie> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Movie m = movies.get(i);
            double score = n > 0 ? (double) (n - i) / n : 0.0;
            result.add(new RankedMovie(String.valueOf(m.id()), score, i + 1, Map.of()));
        }
        return result;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=OnlineBlendingPipelineTest -DskipTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/online/serving/OnlineBlendingPipeline.java \
        src/test/java/com/recsys/online/serving/OnlineBlendingPipelineTest.java
git commit -m "feat: add OnlineBlendingPipeline adapter (RecommendationQuery → online blend → RecommendationResult)"
```

---

## Task 5: Path 1 — RecSysServer `/v2/recommend`

**Files:**
- Create: `src/main/java/com/recsys/serving/RecommendV2Service.java`
- Modify: `src/main/java/com/recsys/serving/RecSysServer.java`
- Test: `src/test/java/com/recsys/serving/RecSysV2RecommendIntegrationTest.java`
- Test: `src/test/java/com/recsys/serving/RecSysServerRegressionTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
// src/test/java/com/recsys/serving/RecSysV2RecommendIntegrationTest.java
package com.recsys.serving;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.service.recommendation.RecommendationPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecSysV2RecommendIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final RecommendationPipeline mockPipeline = mock(RecommendationPipeline.class);

    static {
        when(mockPipeline.recommend(any())).thenReturn(
                new RecommendationResult("1",
                        List.of(new RankedMovie("42", 0.9, 1, Map.of())),
                        null,
                        Map.of("candidateCount", "10", "rankedCount", "5")));
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/v2/recommend", new RecommendV2Service(mockPipeline));
        }
    };

    @Test
    void validQuery_returns200WithRecommendationResult() throws Exception {
        String body = MAPPER.writeValueAsString(
                new RecommendationQuery("1", 5, Set.of(), null));
        AggregatedHttpResponse r = server.blockingWebClient()
                .execute(com.linecorp.armeria.common.HttpRequest.of(
                        com.linecorp.armeria.common.RequestHeaders.of(
                                com.linecorp.armeria.common.HttpMethod.POST, "/v2/recommend",
                                com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE, "application/json"),
                        com.linecorp.armeria.common.HttpData.ofUtf8(body)));

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"userId\":\"1\"");
        assertThat(r.contentUtf8()).contains("\"itemId\":\"42\"");
    }

    @Test
    void invalidQuery_returns400() throws Exception {
        // empty body triggers BadRequestException in readJsonBody
        AggregatedHttpResponse r = server.blockingWebClient()
                .execute(com.linecorp.armeria.common.HttpRequest.of(
                        com.linecorp.armeria.common.RequestHeaders.of(
                                com.linecorp.armeria.common.HttpMethod.POST, "/v2/recommend",
                                com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE, "application/json"),
                        com.linecorp.armeria.common.HttpData.ofUtf8("")));

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=RecSysV2RecommendIntegrationTest -DskipTests=false
```

Expected: FAIL — `RecommendV2Service` does not exist.

- [ ] **Step 3: Create `RecommendV2Service`**

```java
// src/main/java/com/recsys/serving/RecommendV2Service.java
package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.domain.RecommendationQuery;
import com.recsys.service.recommendation.RecommendationPipeline;

public final class RecommendV2Service extends BaseApiService {

    private final RecommendationPipeline pipeline;

    public RecommendV2Service(RecommendationPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
            try {
                RecommendationQuery query = readJsonBody(agg, RecommendationQuery.class);
                return writeJson(HttpStatus.OK, pipeline.recommend(query));
            } catch (BadRequestException | IllegalArgumentException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in RecommendV2Service", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
```

- [ ] **Step 4: Register `/v2/recommend` in `RecSysServer`**

Open `src/main/java/com/recsys/serving/RecSysServer.java`.
After the `MultiChannelRecallService` and `RecommendationService` are created (around line 85), add:

```java
RecommendationOrchestrator orchestrator = new RecommendationOrchestrator(
        recallService,
        new com.recsys.service.ranking.ScoreRanker(),
        null,
        new com.recsys.service.pagination.CursorPaginationService());
```

Add the import at the top:
```java
import com.recsys.service.recommendation.RecommendationOrchestrator;
import com.recsys.service.ranking.ScoreRanker;
import com.recsys.service.pagination.CursorPaginationService;
```

Then register the new service in the `ServerBuilder` chain after the existing routes:
```java
.service("/v2/recommend", new RecommendV2Service(orchestrator))
```

> **Note:** Before adding `ScoreRanker`, verify it exists: `find src -name ScoreRanker.java`. If it doesn't exist, use any available `CandidateRanker` implementation. Check `src/main/java/com/recsys/service/ranking/` for available rankers.

- [ ] **Step 5: Write regression test**

```java
// src/test/java/com/recsys/serving/RecSysServerRegressionTest.java
package com.recsys.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.Movie;
import com.recsys.domain.User;
import com.recsys.service.retrieval.MultiChannelRecallService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class RecSysServerRegressionTest {

    static final com.recsys.infrastructure.DataManager mockData =
            mock(com.recsys.infrastructure.DataManager.class);

    static {
        when(mockData.getUserById(anyInt())).thenReturn(new User(1, "Alice"));
        when(mockData.getMovieById(anyInt())).thenReturn(
                new Movie(1, "Test Movie", 2020, List.of("Action")));
        when(mockData.getWatchedMovieIds(anyInt())).thenReturn(List.of());
        when(mockData.getTopRatedMovies(anyInt())).thenReturn(List.of());
        when(mockData.getMoviesByGenre(any(), anyInt())).thenReturn(List.of());
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            MultiChannelRecallService recall = mock(MultiChannelRecallService.class);
            when(recall.recall(any(), anyInt())).thenReturn(List.of());
            sb.service("/getrecommendation", new RecommendationService(mockData, recall))
              .service("/recommendation",    new RecommendationService(mockData, recall));
        }
    };

    @Test
    void oldGetRecommendation_stillReturnsUserAndMovies() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/getrecommendation?userId=1&k=5");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"user\"");
        assertThat(r.contentUtf8()).contains("\"movies\"");
    }

    @Test
    void oldRecommendationAlias_stillWorks() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/recommendation?userId=1&k=5");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
    }
}
```

- [ ] **Step 6: Run all tests**

```bash
mvn test -Dtest=RecSysV2RecommendIntegrationTest,RecSysServerRegressionTest,RecSysServerIntegrationTest -DskipTests=false
```

Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/serving/RecommendV2Service.java \
        src/main/java/com/recsys/serving/RecSysServer.java \
        src/test/java/com/recsys/serving/RecSysV2RecommendIntegrationTest.java \
        src/test/java/com/recsys/serving/RecSysServerRegressionTest.java
git commit -m "feat: add POST /v2/recommend to RecSysServer (Path 1 — embedding recall)"
```

---

## Task 6: Path 2 + 4 — ModelApplication `/v2/recommend` + Sequential Stub

**Files:**
- Create: `src/main/java/com/recsys/model/controller/RecommendationV2Controller.java`
- Test: `src/test/java/com/recsys/model/controller/ModelV2RecommendIntegrationTest.java`
- Test: `src/test/java/com/recsys/model/controller/SequentialStubIntegrationTest.java`
- Test: `src/test/java/com/recsys/model/controller/RecommendationControllerRegressionTest.java`

`RecommendationQuery` is a record — Jackson deserializes it via its canonical constructor. The compact constructor throws `IllegalArgumentException` for invalid inputs, which Spring's `GlobalExceptionHandler` maps to 400.

`OnnxInferencePipeline` and `SequentialRecommendationPipeline` are instantiated directly in the controller constructor — no `@Autowired` needed since `RecommendationService` and `ABTestService` are already Spring beans injected into the controller.

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/com/recsys/model/controller/ModelV2RecommendIntegrationTest.java
package com.recsys.model.controller;

import com.recsys.model.dto.ScoredItem;
import com.recsys.model.request.RecommendRequest;
import com.recsys.model.response.RecommendResponse;
import com.recsys.model.service.ABTestService;
import com.recsys.model.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ModelV2RecommendIntegrationTest {

    @Autowired MockMvc mockMvc;
    // Mock the underlying services — the controller creates OnnxInferencePipeline from these.
    @MockBean RecommendationService recommendationService;
    @MockBean ABTestService abTestService;

    @Test
    void validRequest_returns200WithRecommendationResult() throws Exception {
        when(abTestService.getAssignmentForUser("u1")).thenReturn(
                new ABTestService.Assignment("u1", "training", 0));
        when(recommendationService.recommend(any(RecommendRequest.class), any())).thenReturn(
                new RecommendResponse("u1", "v1.0", "training",
                        List.of(new ScoredItem("42", 0.9))));

        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u1"))
                .andExpect(jsonPath("$.items[0].itemId").value("42"))
                .andExpect(jsonPath("$.trace.abTestVariant").value("training"));
    }

    @Test
    void invalidUserId_returns400() throws Exception {
        // blank userId triggers IllegalArgumentException from RecommendationQuery compact constructor
        mockMvc.perform(post("/v2/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isBadRequest());
    }
}
```

```java
// src/test/java/com/recsys/model/controller/SequentialStubIntegrationTest.java
package com.recsys.model.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
class SequentialStubIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void sequentialEndpoint_returns501NotImplemented() throws Exception {
        mockMvc.perform(post("/v2/sequential/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error", containsString("not yet implemented")));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=ModelV2RecommendIntegrationTest,SequentialStubIntegrationTest -DskipTests=false
```

Expected: FAIL — controller does not exist.

- [ ] **Step 3: Create `RecommendationV2Controller`**

```java
// src/main/java/com/recsys/model/controller/RecommendationV2Controller.java
package com.recsys.model.controller;

import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.model.service.ABTestService;
import com.recsys.model.service.OnnxInferencePipeline;
import com.recsys.model.service.RecommendationService;
import com.recsys.service.recommendation.RecommendationPipeline;
import com.recsys.service.recommendation.SequentialRecommendationPipeline;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class RecommendationV2Controller {

    private final RecommendationPipeline onnxPipeline;
    private final RecommendationPipeline sequentialPipeline;

    public RecommendationV2Controller(RecommendationService recommendationService,
                                       ABTestService abTestService) {
        this.onnxPipeline = new OnnxInferencePipeline(recommendationService, abTestService);
        this.sequentialPipeline = new SequentialRecommendationPipeline();
    }

    @PostMapping(
            value = "/v2/recommend",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<RecommendationResult> recommend(@RequestBody RecommendationQuery query) {
        return ResponseEntity.ok(onnxPipeline.recommend(query));
    }

    @PostMapping(
            value = "/v2/sequential/recommend",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<RecommendationResult> recommendSequential(
            @RequestBody RecommendationQuery query) {
        return ResponseEntity.ok(sequentialPipeline.recommend(query));
    }
}

- [ ] **Step 4: Write regression test**

```java
// src/test/java/com/recsys/model/controller/RecommendationControllerRegressionTest.java
package com.recsys.model.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RecommendationControllerRegressionTest {

    @Autowired MockMvc mockMvc;

    @Test
    void oldApiV1Recommend_stillReturnsLegacyShape() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"123\",\"k\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("123"))
                .andExpect(jsonPath("$.modelVersion").exists())
                .andExpect(jsonPath("$.abTestVariant").exists())
                .andExpect(jsonPath("$.recommendations").isArray());
    }
}
```

- [ ] **Step 5: Run all tests**

```bash
mvn test -Dtest=ModelV2RecommendIntegrationTest,SequentialStubIntegrationTest,RecommendationControllerRegressionTest,RecommendationEndToEndTest -DskipTests=false
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/model/controller/RecommendationV2Controller.java \
        src/test/java/com/recsys/model/controller/ModelV2RecommendIntegrationTest.java \
        src/test/java/com/recsys/model/controller/SequentialStubIntegrationTest.java \
        src/test/java/com/recsys/model/controller/RecommendationControllerRegressionTest.java
git commit -m "feat: add POST /v2/recommend and /v2/sequential/recommend to ModelApplication (Paths 2 and 4)"
```

---

## Task 7: Path 3 — OnlinePredictionServer `/v2/recommend`

**Files:**
- Create: `src/main/java/com/recsys/online/serving/OnlineRecommendV2Service.java`
- Modify: `src/main/java/com/recsys/online/serving/OnlinePredictionServer.java`
- Test: `src/test/java/com/recsys/online/serving/OnlineV2RecommendIntegrationTest.java`
- Test: `src/test/java/com/recsys/online/serving/OnlinePredictionRegressionTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/com/recsys/online/serving/OnlineV2RecommendIntegrationTest.java
package com.recsys.online.serving;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.Movie;
import com.recsys.domain.User;
import com.recsys.service.recommendation.RecommendationPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineV2RecommendIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final OnlineRecommendationService mockService =
            mock(OnlineRecommendationService.class);

    static {
        when(mockService.recommend(any())).thenReturn(
                new OnlineRecommendationResult(
                        new User(1, "Alice"), "last_hour", "online+model",
                        List.of(), List.of(),
                        List.of(new Movie(10, "Inception", 2010, List.of("Sci-Fi")))));
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            RecommendationPipeline pipeline = new OnlineBlendingPipeline(mockService);
            sb.service("/v2/recommend", new OnlineRecommendV2Service(pipeline));
        }
    };

    @Test
    void validQuery_returns200WithRecommendationResult() throws Exception {
        String body = MAPPER.writeValueAsString(
                new com.recsys.domain.RecommendationQuery("1", 5, Set.of(), null));
        AggregatedHttpResponse r = server.blockingWebClient()
                .execute(com.linecorp.armeria.common.HttpRequest.of(
                        com.linecorp.armeria.common.RequestHeaders.of(
                                com.linecorp.armeria.common.HttpMethod.POST, "/v2/recommend",
                                com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE, "application/json"),
                        com.linecorp.armeria.common.HttpData.ofUtf8(body)));

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"userId\":\"1\"");
        assertThat(r.contentUtf8()).contains("\"strategy\"");
        assertThat(r.contentUtf8()).contains("\"window\"");
    }

    @Test
    void nonNumericUserId_returns400() throws Exception {
        String body = MAPPER.writeValueAsString(
                new com.recsys.domain.RecommendationQuery("not-a-number", 5, Set.of(), null));
        AggregatedHttpResponse r = server.blockingWebClient()
                .execute(com.linecorp.armeria.common.HttpRequest.of(
                        com.linecorp.armeria.common.RequestHeaders.of(
                                com.linecorp.armeria.common.HttpMethod.POST, "/v2/recommend",
                                com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE, "application/json"),
                        com.linecorp.armeria.common.HttpData.ofUtf8(body)));

        assertThat(r.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=OnlineV2RecommendIntegrationTest -DskipTests=false
```

Expected: FAIL — class does not exist.

- [ ] **Step 3: Create `OnlineRecommendV2Service`**

```java
// src/main/java/com/recsys/online/serving/OnlineRecommendV2Service.java
package com.recsys.online.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.domain.RecommendationQuery;
import com.recsys.service.recommendation.RecommendationPipeline;

public final class OnlineRecommendV2Service extends ApiService {

    private final RecommendationPipeline pipeline;

    public OnlineRecommendV2Service(RecommendationPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
            try {
                RecommendationQuery query = readJsonBody(agg, RecommendationQuery.class);
                return writeJson(HttpStatus.OK, pipeline.recommend(query));
            } catch (BadRequestException | IllegalArgumentException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in OnlineRecommendV2Service", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
```

- [ ] **Step 4: Register `/v2/recommend` in `OnlinePredictionServer`**

Open `src/main/java/com/recsys/online/serving/OnlinePredictionServer.java`.

After `recommendationService` is created (around line 55), add:

```java
OnlineBlendingPipeline blendingPipeline = new OnlineBlendingPipeline(recommendationService);
```

Then in the `ServerBuilder` chain, add after the existing routes:

```java
.service("/v2/recommend", new OnlineRecommendV2Service(blendingPipeline))
```

- [ ] **Step 5: Write regression test**

```java
// src/test/java/com/recsys/online/serving/OnlinePredictionRegressionTest.java
package com.recsys.online.serving;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.recsys.domain.Movie;
import com.recsys.domain.User;
import com.recsys.online.ops.OnlineLoadShedder;
import com.recsys.online.ops.OnlineServingMetricsService;
import com.recsys.online.redis.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlinePredictionRegressionTest {

    static final OnlineRecommendationService mockService =
            mock(OnlineRecommendationService.class);

    static {
        when(mockService.recommend(any())).thenReturn(
                new OnlineRecommendationResult(
                        new User(1, "Alice"), "last_hour", "online",
                        List.of(), List.of(),
                        List.of(new Movie(10, "Inception", 2010, List.of()))));
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            OnlineServingMetricsService metrics = new OnlineServingMetricsService();
            OnlineLoadShedder shedder = new OnlineLoadShedder();
            sb.service("/online/recommendation",
                    new OnlinePredictionService(mockService, metrics, shedder,
                            RedisRateLimiter.disabled(), true));
        }
    };

    @Test
    void oldOnlineRecommendation_stillReturnsLegacyShape() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .get("/online/recommendation?userId=1&k=5");
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).contains("\"strategy\"");
        assertThat(r.contentUtf8()).contains("\"window\"");
        assertThat(r.contentUtf8()).contains("\"recommendations\"");
        assertThat(r.contentUtf8()).contains("\"recentMovies\"");
    }
}
```

- [ ] **Step 6: Run all tests**

```bash
mvn test -Dtest=OnlineV2RecommendIntegrationTest,OnlinePredictionRegressionTest,OnlinePredictionServerIntegrationTest -DskipTests=false
```

Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/online/serving/OnlineRecommendV2Service.java \
        src/main/java/com/recsys/online/serving/OnlinePredictionServer.java \
        src/test/java/com/recsys/online/serving/OnlineV2RecommendIntegrationTest.java \
        src/test/java/com/recsys/online/serving/OnlinePredictionRegressionTest.java
git commit -m "feat: add POST /v2/recommend to OnlinePredictionServer (Path 3 — online blending)"
```

---

## Task 8: Gateway Route Table

**Files:**
- Modify: `src/main/java/com/recsys/microservice/MicroserviceRoute.java`
- Test: `src/test/java/com/recsys/microservice/GatewayRouteTableTest.java` (extend or replace existing `MicroserviceRouteTest.java`)

- [ ] **Step 1: Write failing test**

```java
// src/test/java/com/recsys/microservice/GatewayRouteTableTest.java
package com.recsys.microservice;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteTableTest {

    private final List<MicroserviceRoute> routes = MicroserviceRoute.defaults();

    @Test
    void noDuplicatePrefixes() {
        List<String> prefixes = routes.stream()
                .map(MicroserviceRoute::prefix).toList();
        Set<String> unique = Set.copyOf(prefixes);
        assertThat(prefixes).hasSameSizeAs(unique);
    }

    @Test
    void deadRoutesAreRemoved() {
        Set<String> prefixes = routes.stream()
                .map(MicroserviceRoute::prefix)
                .collect(Collectors.toSet());
        assertThat(prefixes).doesNotContain(
                "/api/retrieval",
                "/api/ranking",
                "/api/agents",
                "/api/observability");
    }

    @Test
    void newProductionRoutesExist() {
        Set<String> prefixes = routes.stream()
                .map(MicroserviceRoute::prefix)
                .collect(Collectors.toSet());
        assertThat(prefixes).contains(
                "/api/recommend/embedding",
                "/api/recommend/model",
                "/api/recommend/online",
                "/api/recommend/sequential",
                "/api/knowledge");
    }

    @Test
    void backwardCompatRoutesAreKept() {
        Set<String> prefixes = routes.stream()
                .map(MicroserviceRoute::prefix)
                .collect(Collectors.toSet());
        assertThat(prefixes).contains(
                "/api/catalog",
                "/api/model",
                "/api/online",
                "/api/users",
                "/api/movies",
                "/api/features");
    }

    @Test
    void recommendPrefixRoutesRewriteToV2Endpoint() {
        MicroserviceRoute embedding = routes.stream()
                .filter(r -> r.prefix().equals("/api/recommend/embedding"))
                .findFirst().orElseThrow();
        // /api/recommend/embedding/v2/recommend → :6010/v2/recommend
        assertThat(embedding.rewrite("/api/recommend/embedding/v2/recommend", null).getPath())
                .isEqualTo("/v2/recommend");
        assertThat(embedding.baseUri().getPort()).isEqualTo(6010);

        MicroserviceRoute model = routes.stream()
                .filter(r -> r.prefix().equals("/api/recommend/model"))
                .findFirst().orElseThrow();
        assertThat(model.rewrite("/api/recommend/model/v2/recommend", null).getPath())
                .isEqualTo("/v2/recommend");
        assertThat(model.baseUri().getPort()).isEqualTo(8080);

        MicroserviceRoute online = routes.stream()
                .filter(r -> r.prefix().equals("/api/recommend/online"))
                .findFirst().orElseThrow();
        assertThat(online.rewrite("/api/recommend/online/v2/recommend", null).getPath())
                .isEqualTo("/v2/recommend");
        assertThat(online.baseUri().getPort()).isEqualTo(7010);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=GatewayRouteTableTest -DskipTests=false
```

Expected: FAIL — dead routes still present, new routes absent.

- [ ] **Step 3: Rewrite `buildDefaults()` in `MicroserviceRoute.java`**

Open `src/main/java/com/recsys/microservice/MicroserviceRoute.java`.
Replace the body of `buildDefaults()` (lines 16-34) with:

```java
private static List<MicroserviceRoute> buildDefaults() {
    List<MicroserviceRoute> routes = new java.util.ArrayList<>();

    // ── Production recommendation routes (new — all backends expose POST /v2/recommend) ──
    routes.add(fromEnv("embed-recall",    "/api/recommend/embedding",   "EMBED_RECALL_SERVICE_URL",    "http://localhost:6010", "/health"));
    routes.add(fromEnv("model-inference", "/api/recommend/model",       "MODEL_INFERENCE_SERVICE_URL", "http://localhost:8080", "/health/ready"));
    routes.add(fromEnv("online-blend",    "/api/recommend/online",      "ONLINE_BLEND_SERVICE_URL",    "http://localhost:7010", "/health"));
    routes.add(fromEnv("sequential",      "/api/recommend/sequential",  "SEQUENTIAL_SERVICE_URL",      "http://localhost:8080", "/health/ready"));

    // ── Data / catalog routes ──
    routes.add(fromEnv("user-profile",    "/api/users",    "USER_PROFILE_SERVICE_URL",    "http://localhost:6010", "/health"));
    routes.add(fromEnv("movie-metadata",  "/api/movies",   "MOVIE_METADATA_SERVICE_URL",  "http://localhost:6010", "/health"));
    routes.add(fromEnv("feature",         "/api/features", "FEATURE_SERVICE_URL",          "http://localhost:7010", "/health"));
    routes.add(fromEnv("knowledge",       "/api/knowledge","KNOWLEDGE_SERVICE_URL",        "http://localhost:8080", "/health/ready"));

    // ── Backward-compatible routes (kept for dev and legacy clients) ──
    routes.add(fromEnv("catalog", "/api/catalog", "CATALOG_SERVICE_URL", "http://localhost:6010", "/health"));
    routes.add(fromEnv("model",   "/api/model",   "MODEL_SERVICE_URL",   "http://localhost:8080", "/health/ready"));
    routes.add(fromEnv("online",  "/api/online",  "ONLINE_SERVICE_URL",  "http://localhost:7010", "/health"));

    // ── LLM routes — optional; only registered when env var is set ──
    fromEnvOptional("llm-explanation", "/api/explanations", "LLM_EXPLANATION_SERVICE_URL", "/api/tags").ifPresent(routes::add);
    fromEnvOptional("llm",             "/api/llm",          "LLM_SERVICE_URL",             "/api/tags").ifPresent(routes::add);

    return List.copyOf(routes);
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -Dtest=GatewayRouteTableTest,MicroserviceRouteTest,GatewayServerIntegrationTest -DskipTests=false
```

Expected: all PASS. If `MicroserviceRouteTest` asserts specific route counts or names that no longer match, update those assertions to match the new table.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/microservice/MicroserviceRoute.java \
        src/test/java/com/recsys/microservice/GatewayRouteTableTest.java
git commit -m "fix: overhaul gateway route table — remove 4 dead routes, add 5 production routes"
```

---

## Task 9: Knowledge Base Package Rename

**Files:** Move 10 source files + 3 test files to `model/knowledge/`.

No logic changes — only `package` declarations and import statements.

- [ ] **Step 1: Create target directory structure**

```bash
mkdir -p src/main/java/com/recsys/model/knowledge
mkdir -p src/test/java/com/recsys/model/knowledge
```

- [ ] **Step 2: Move source files**

```bash
# Controllers
mv src/main/java/com/recsys/model/controller/KnowledgeBaseController.java \
   src/main/java/com/recsys/model/knowledge/KnowledgeBaseController.java

# Services
mv src/main/java/com/recsys/model/service/KnowledgeBaseFacadeService.java \
   src/main/java/com/recsys/model/knowledge/KnowledgeBaseFacadeService.java

# Converter
mv src/main/java/com/recsys/model/converter/KnowledgeBaseConverter.java \
   src/main/java/com/recsys/model/knowledge/KnowledgeBaseConverter.java

# DTOs / entities / requests / responses / VOs
mv src/main/java/com/recsys/model/dto/KnowledgeBaseDTO.java \
   src/main/java/com/recsys/model/knowledge/KnowledgeBaseDTO.java
mv src/main/java/com/recsys/model/entity/KnowledgeBase.java \
   src/main/java/com/recsys/model/knowledge/KnowledgeBase.java
mv src/main/java/com/recsys/model/request/CreateKnowledgeBaseRequest.java \
   src/main/java/com/recsys/model/knowledge/CreateKnowledgeBaseRequest.java
mv src/main/java/com/recsys/model/request/UpdateKnowledgeBaseRequest.java \
   src/main/java/com/recsys/model/knowledge/UpdateKnowledgeBaseRequest.java
mv src/main/java/com/recsys/model/response/CreateKnowledgeBaseResponse.java \
   src/main/java/com/recsys/model/knowledge/CreateKnowledgeBaseResponse.java
mv src/main/java/com/recsys/model/response/GetKnowledgeBasesResponse.java \
   src/main/java/com/recsys/model/knowledge/GetKnowledgeBasesResponse.java
mv src/main/java/com/recsys/model/vo/KnowledgeBaseVO.java \
   src/main/java/com/recsys/model/knowledge/KnowledgeBaseVO.java
```

- [ ] **Step 3: Move test files**

```bash
mv src/test/java/com/recsys/model/controller/KnowledgeBaseControllerTest.java \
   src/test/java/com/recsys/model/knowledge/KnowledgeBaseControllerTest.java
mv src/test/java/com/recsys/model/converter/KnowledgeBaseConverterTest.java \
   src/test/java/com/recsys/model/knowledge/KnowledgeBaseConverterTest.java
mv src/test/java/com/recsys/model/service/KnowledgeBaseFacadeServiceTest.java \
   src/test/java/com/recsys/model/knowledge/KnowledgeBaseFacadeServiceTest.java
```

- [ ] **Step 4: Update `package` declarations and imports in all moved files**

For each moved file, change `package com.recsys.model.controller;` (or `service`, `converter`, `dto`, `entity`, `request`, `response`, `vo`) to:

```java
package com.recsys.model.knowledge;
```

Update cross-imports within the moved files (e.g., `KnowledgeBaseController` imports `KnowledgeBaseFacadeService` — update to `com.recsys.model.knowledge.KnowledgeBaseFacadeService`).

Run the following to find all files that import the old packages and need updating:

```bash
grep -rl "com.recsys.model.controller.KnowledgeBase\|com.recsys.model.service.KnowledgeBase\|com.recsys.model.converter.KnowledgeBase\|com.recsys.model.dto.KnowledgeBase\|com.recsys.model.entity.KnowledgeBase\|com.recsys.model.request.CreateKnowledge\|com.recsys.model.request.UpdateKnowledge\|com.recsys.model.response.CreateKnowledge\|com.recsys.model.response.GetKnowledge\|com.recsys.model.vo.KnowledgeBase" src/
```

Update each file found to import from `com.recsys.model.knowledge.*` instead.

- [ ] **Step 5: Build and verify**

```bash
mvn compile -DskipTests && mvn test -Dtest="com.recsys.model.knowledge.*" -DskipTests=false
```

Expected: compile succeeds; all knowledge base tests PASS.

- [ ] **Step 6: Run full test suite**

```bash
mvn test
```

Expected: all tests PASS (no regressions from the rename).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/model/knowledge/ \
        src/test/java/com/recsys/model/knowledge/ \
        src/main/java/com/recsys/model/controller/ \
        src/main/java/com/recsys/model/service/ \
        src/main/java/com/recsys/model/converter/ \
        src/main/java/com/recsys/model/dto/ \
        src/main/java/com/recsys/model/entity/ \
        src/main/java/com/recsys/model/request/ \
        src/main/java/com/recsys/model/response/ \
        src/main/java/com/recsys/model/vo/ \
        src/test/java/com/recsys/model/controller/ \
        src/test/java/com/recsys/model/converter/ \
        src/test/java/com/recsys/model/service/
git commit -m "refactor: move knowledge base classes to model/knowledge/ package"
```

---

## Task 10: Cross-Path Consistency + Embedding Recall Load Test

**Files:**
- Test: `src/test/java/com/recsys/service/recommendation/CrossPathConsistencyTest.java`
- Test: `src/test/java/com/recsys/serving/EmbeddingRecallLoadTest.java`

- [ ] **Step 1: Write `CrossPathConsistencyTest`**

This test starts a minimal Armeria server for Paths 1 and 3, and uses `MockMvc` for Path 2. It sends the same `userId` to all three and asserts each returns a valid `RecommendationResult`.

```java
// src/test/java/com/recsys/service/recommendation/CrossPathConsistencyTest.java
package com.recsys.service.recommendation;

import com.recsys.domain.RankedMovie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossPathConsistencyTest {

    @Test
    void allThreePipelines_returnNonEmptyResultForSameUserId() {
        RecommendationQuery query = new RecommendationQuery("1", 5, Set.of(), null);

        // Path 1 — embedding recall via mocked orchestrator
        MultiChannelRecallService recall = mock(com.recsys.service.retrieval.MultiChannelRecallService.class);
        com.recsys.service.ranking.CandidateRanker ranker = mock(com.recsys.service.ranking.CandidateRanker.class);
        com.recsys.service.pagination.CursorPaginationService pagination =
                mock(com.recsys.service.pagination.CursorPaginationService.class);
        RankedMovie rm = new RankedMovie("10", 0.9, 1, Map.of());
        when(recall.recall(any(), anyInt())).thenReturn(List.of(mock(com.recsys.domain.MovieCandidate.class)));
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of(rm));
        when(pagination.page(any(), any(), anyInt()))
                .thenReturn(new com.recsys.service.pagination.Page<>(List.of(rm), null));
        RecommendationPipeline path1 = new RecommendationOrchestrator(
                recall, ranker, com.recsys.service.hydrator.RecommendationHydrator.IDENTITY, pagination);
        RecommendationResult r1 = path1.recommend(query);

        // Path 2 — ONNX pipeline via mocked service
        com.recsys.model.service.RecommendationService onnxService =
                mock(com.recsys.model.service.RecommendationService.class);
        com.recsys.model.service.ABTestService abTest =
                mock(com.recsys.model.service.ABTestService.class);
        when(abTest.getAssignmentForUser(any())).thenReturn(
                new com.recsys.model.service.ABTestService.Assignment("1", "training", 0));
        when(onnxService.recommend(any(), any())).thenReturn(
                new com.recsys.model.response.RecommendResponse(
                        "1", "v1", "training",
                        List.of(new com.recsys.model.dto.ScoredItem("42", 0.8))));
        RecommendationPipeline path2 =
                new com.recsys.model.service.OnnxInferencePipeline(onnxService, abTest);
        RecommendationResult r2 = path2.recommend(query);

        // Path 3 — online blending via mocked service
        com.recsys.online.serving.OnlineRecommendationService onlineService =
                mock(com.recsys.online.serving.OnlineRecommendationService.class);
        when(onlineService.recommend(any())).thenReturn(
                new com.recsys.online.serving.OnlineRecommendationResult(
                        new com.recsys.domain.User(1, "Alice"), "last_hour", "online",
                        List.of(), List.of(),
                        List.of(new com.recsys.domain.Movie(7, "Film", 2020, List.of()))));
        RecommendationPipeline path3 =
                new com.recsys.online.serving.OnlineBlendingPipeline(onlineService);
        RecommendationResult r3 = path3.recommend(query);

        // All three must return the same userId and non-empty items
        for (RecommendationResult result : List.of(r1, r2, r3)) {
            assertThat(result.userId()).isEqualTo("1");
            assertThat(result.items()).isNotEmpty();
            assertThat(result.items().get(0).rank()).isEqualTo(1);
        }
    }

    // The import for anyInt() needs to be added:
    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
```

- [ ] **Step 2: Write `EmbeddingRecallLoadTest`**

```java
// src/test/java/com/recsys/serving/EmbeddingRecallLoadTest.java
package com.recsys.serving;

import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.RecommendationResult;
import com.recsys.service.recommendation.RecommendationOrchestrator;
import com.recsys.service.recommendation.RecommendationPipeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("load")
class EmbeddingRecallLoadTest {

    private static final int CONCURRENCY   = 20;
    private static final int TOTAL         = 200;
    private static final long TIMEOUT_S    = 60L;
    private static final long MAX_P95_MS   = 500L;
    private static final double MIN_SUCCESS = 0.99;

    @Test
    @Timeout(value = TIMEOUT_S + 10)
    void concurrentRequests_p95Under500ms() throws InterruptedException {
        // Stub a fast pipeline — measures framework overhead, not business logic.
        com.recsys.service.retrieval.MultiChannelRecallService recall =
                mock(com.recsys.service.retrieval.MultiChannelRecallService.class);
        com.recsys.service.ranking.CandidateRanker ranker =
                mock(com.recsys.service.ranking.CandidateRanker.class);
        com.recsys.service.pagination.CursorPaginationService pagination =
                mock(com.recsys.service.pagination.CursorPaginationService.class);
        when(recall.recall(any(), anyInt())).thenReturn(List.of());
        when(ranker.rank(any(), any(), anyInt())).thenReturn(List.of());
        when(pagination.page(any(), any(), anyInt()))
                .thenReturn(new com.recsys.service.pagination.Page<>(List.of(), null));

        RecommendationPipeline pipeline = new RecommendationOrchestrator(
                recall, ranker,
                com.recsys.service.hydrator.RecommendationHydrator.IDENTITY, pagination);

        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TOTAL);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        for (int i = 0; i < TOTAL; i++) {
            final String uid = String.valueOf(i % 50 + 1);
            pool.submit(() -> {
                try {
                    startGate.await();
                    long t0 = System.nanoTime();
                    try {
                        pipeline.recommend(new RecommendationQuery(uid, 10, Set.of(), null));
                        latencies.add((System.nanoTime() - t0) / 1_000_000L);
                    } catch (RuntimeException e) {
                        latencies.add((System.nanoTime() - t0) / 1_000_000L);
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        boolean allDone = done.await(TIMEOUT_S, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(allDone).as("all %d requests completed", TOTAL).isTrue();

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        long p95 = sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
        double successRate = 1.0 - (double) errors.get() / sorted.size();

        System.out.printf("[LOAD] EmbeddingRecall: p95=%dms success=%.1f%%%n",
                p95, successRate * 100);

        assertThat(p95).as("P95 latency").isLessThanOrEqualTo(MAX_P95_MS);
        assertThat(successRate).as("success rate").isGreaterThanOrEqualTo(MIN_SUCCESS);
    }
}
```

- [ ] **Step 3: Run consistency test**

```bash
mvn test -Dtest=CrossPathConsistencyTest -DskipTests=false
```

Expected: PASS.

- [ ] **Step 4: Run load test**

```bash
mvn test -DexcludedGroups="" -Dgroups=load -Dtest=EmbeddingRecallLoadTest -DskipTests=false
```

Expected: PASS — P95 ≤ 500 ms, success ≥ 99%.

- [ ] **Step 5: Run full suite to confirm no regressions**

```bash
mvn test
```

Expected: all tests PASS.

- [ ] **Step 6: Write `V2CrossPathLoadTest`**

```java
// src/test/java/com/recsys/service/recommendation/V2CrossPathLoadTest.java
package com.recsys.service.recommendation;

import com.recsys.domain.Movie;
import com.recsys.domain.RecommendationQuery;
import com.recsys.domain.User;
import com.recsys.model.dto.ScoredItem;
import com.recsys.model.response.RecommendResponse;
import com.recsys.model.service.ABTestService;
import com.recsys.model.service.OnnxInferencePipeline;
import com.recsys.online.serving.OnlineBlendingPipeline;
import com.recsys.online.serving.OnlineRecommendationRequest;
import com.recsys.online.serving.OnlineRecommendationResult;
import com.recsys.online.serving.OnlineRecommendationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("load")
class V2CrossPathLoadTest {

    private static final int CONCURRENCY  = 15;
    private static final int TOTAL        = 150;
    private static final long TIMEOUT_S   = 60L;
    private static final long MAX_P95_MS  = 500L;
    private static final double MIN_SUCCESS = 0.99;

    @Test
    @Timeout(value = TIMEOUT_S + 10)
    void allThreePipelines_steadyLoad_p95Under500ms() throws InterruptedException {
        // Path 2 — ONNX pipeline
        com.recsys.model.service.RecommendationService onnxService =
                mock(com.recsys.model.service.RecommendationService.class);
        ABTestService abTest = mock(ABTestService.class);
        when(abTest.getAssignmentForUser(any()))
                .thenReturn(new ABTestService.Assignment("1", "training", 0));
        when(onnxService.recommend(any(), any())).thenReturn(
                new RecommendResponse("1", "v1", "training",
                        List.of(new ScoredItem("42", 0.9))));
        RecommendationPipeline onnx = new OnnxInferencePipeline(onnxService, abTest);

        // Path 3 — online blending pipeline
        OnlineRecommendationService onlineService = mock(OnlineRecommendationService.class);
        when(onlineService.recommend(any())).thenReturn(
                new OnlineRecommendationResult(
                        new User(1, "Alice"), "last_hour", "online",
                        List.of(), List.of(),
                        List.of(new Movie(7, "Film", 2020, List.of()))));
        RecommendationPipeline online = new OnlineBlendingPipeline(onlineService);

        // Path 1 — orchestrator (minimal stubs)
        com.recsys.service.retrieval.MultiChannelRecallService recall =
                mock(com.recsys.service.retrieval.MultiChannelRecallService.class);
        com.recsys.service.ranking.CandidateRanker ranker =
                mock(com.recsys.service.ranking.CandidateRanker.class);
        com.recsys.service.pagination.CursorPaginationService pagination =
                mock(com.recsys.service.pagination.CursorPaginationService.class);
        when(recall.recall(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(ranker.rank(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(pagination.page(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.recsys.service.pagination.Page<>(List.of(), null));
        RecommendationPipeline embedding = new RecommendationOrchestrator(
                recall, ranker,
                com.recsys.service.hydrator.RecommendationHydrator.IDENTITY, pagination);

        List<RecommendationPipeline> pipelines = List.of(embedding, onnx, online);
        // Queries: paths 2 and 3 require numeric userId; path 1 accepts any string.
        // All three accept numeric strings, so use numeric user IDs throughout.
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TOTAL);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        for (int i = 0; i < TOTAL; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    RecommendationPipeline p = pipelines.get(idx % pipelines.size());
                    String uid = String.valueOf(idx % 10 + 1);
                    long t0 = System.nanoTime();
                    try {
                        p.recommend(new RecommendationQuery(uid, 5, Set.of(), null));
                        latencies.add((System.nanoTime() - t0) / 1_000_000L);
                    } catch (RuntimeException e) {
                        latencies.add((System.nanoTime() - t0) / 1_000_000L);
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        boolean allDone = done.await(TIMEOUT_S, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(allDone).as("all %d requests completed", TOTAL).isTrue();

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        long p95 = sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
        double successRate = 1.0 - (double) errors.get() / sorted.size();

        System.out.printf("[LOAD] V2CrossPath: p95=%dms success=%.1f%%%n",
                p95, successRate * 100);

        assertThat(p95).as("P95 latency").isLessThanOrEqualTo(MAX_P95_MS);
        assertThat(successRate).as("success rate").isGreaterThanOrEqualTo(MIN_SUCCESS);
    }
}
```

- [ ] **Step 7: Run load tests**

```bash
mvn test -DexcludedGroups="" -Dgroups=load \
    -Dtest=EmbeddingRecallLoadTest,V2CrossPathLoadTest -DskipTests=false
```

Expected: both PASS — P95 ≤ 500 ms, success ≥ 99%.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/com/recsys/service/recommendation/CrossPathConsistencyTest.java \
        src/test/java/com/recsys/serving/EmbeddingRecallLoadTest.java \
        src/test/java/com/recsys/service/recommendation/V2CrossPathLoadTest.java
git commit -m "test: add cross-path consistency test, EmbeddingRecall load test, and V2CrossPath load test"
```

---

## Final Verification

- [ ] **Run the full test suite one final time**

```bash
mvn test
```

Expected: all non-load tests PASS.

- [ ] **Run load tests**

```bash
mvn test -DexcludedGroups="" -Dgroups=load
```

Expected: all load tests PASS.

- [ ] **Verify new endpoints are registered (spot check)**

```bash
mvn exec:java -Dexec.mainClass=com.recsys.serving.RecSysServer &
sleep 3
curl -s -X POST http://localhost:6010/v2/recommend \
     -H "Content-Type: application/json" \
     -d '{"userId":"1","limit":5,"excludedItemIds":[],"cursor":null}' | jq .
kill %1
```

Expected: JSON response with `userId`, `items`, `nextCursor`, `trace`.

- [ ] **Verify gateway health reports clean route table**

```bash
mvn exec:java -Dexec.mainClass=com.recsys.microservice.MicroserviceGatewayServer &
sleep 2
curl -s http://localhost:8010/health | jq '.routes | keys'
kill %1
```

Expected: keys include `embed-recall`, `model-inference`, `online-blend`, `sequential`, `knowledge`; do NOT include `recommendation-retrieval`, `ranking`, `agent-workflow`, `observability`.
