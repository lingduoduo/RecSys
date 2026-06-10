# Modelbased Structure Optimization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the `modelbased` package with the knowledge-base pattern so every domain uses consistent `controller/`, `converter/`, `request/`, `response/`, `dto/`, `entity/`, `vo/`, and `service/` layers, and exceptions move out of `service/` into a dedicated `exception/` package.

**Architecture:** Currently `dto/` is a mixed bag: it holds internal data types (`KnowledgeBaseDTO`, `ScoredItem`, `ApiError`) alongside API-facing request/response objects (`RecommendRequest`, `RecommendResponse`, `ModelVersionRequest`, `ModelVersionResponse`, `SubmitTokenResponse`) that should live in `request/` and `response/`. Three exception classes (`RateLimitExceededException`, `ServiceOverloadedException`, `SubmitTokenException`) are stranded in `service/`. A new `converter/RecommendationConverter` will own all request-normalization and response-building helpers currently inlined as statics in `RecommendationService`.

**Tech Stack:** Java 21, Spring Boot 3, Jakarta Validation, Maven

---

## File Map (before → after)

| Old path | New path | Action |
|---|---|---|
| `dto/RecommendRequest.java` | `request/RecommendRequest.java` | move (pkg change) |
| `dto/ModelVersionRequest.java` | `request/ModelVersionRequest.java` | move (pkg change) |
| `dto/RecommendResponse.java` | `response/RecommendResponse.java` | move (pkg change) |
| `dto/SubmitTokenResponse.java` | `response/SubmitTokenResponse.java` | move (pkg change) |
| `dto/ModelVersionResponse.java` | `response/ModelVersionResponse.java` | move (pkg change) |
| `service/RateLimitExceededException.java` | `exception/RateLimitExceededException.java` | move (pkg change) |
| `service/ServiceOverloadedException.java` | `exception/ServiceOverloadedException.java` | move (pkg change) |
| `service/SubmitTokenException.java` | `exception/SubmitTokenException.java` | move (pkg change) |
| _(new)_ | `converter/RecommendationConverter.java` | create |

**Stays in `dto/`:** `ApiError`, `ApiResponse`, `KnowledgeBaseDTO`, `ScoredItem`

### Import update matrix

| File | Imports that change |
|---|---|
| `config/GlobalExceptionHandler.java` | `service.Rateimit*` → `exception.*` (3 classes) |
| `controller/RecommendationController.java` | `dto.RecommendRequest/Response/SubmitTokenResponse` → `request/response`; `service.RateLimit/ServiceOverloaded*` → `exception.*` |
| `controller/VersionController.java` | `dto.ModelVersionRequest/Response` → `request/response` |
| `service/FeatureEncoder.java` | `dto.RecommendRequest` → `request.RecommendRequest` |
| `service/ModelVersionService.java` | `dto.ModelVersionResponse` → `response.ModelVersionResponse` |
| `service/RecommendationService.java` | `dto.RecommendRequest/Response` → `request/response`; inject `RecommendationConverter`; remove static helpers |
| `service/SubmitTokenService.java` | `SubmitTokenException` (same pkg, implicit) → `exception.SubmitTokenException` |
| `test/.../RecommendationControllerTest.java` | `dto.RecommendRequest/Response` → `request/response`; `service.SubmitTokenException` → `exception.*` |
| `test/.../RecommendationEndToEndTest.java` | `dto.RecommendRequest/Response` → `request/response` |
| `test/.../VersionControllerTest.java` | `dto.ModelVersionResponse` → `response.ModelVersionResponse` |
| `test/.../FeatureEncoderTest.java` | `dto.RecommendRequest` → `request.RecommendRequest` |
| `test/.../InferenceLoadTest.java` | `dto.RecommendRequest` → `request.RecommendRequest` |
| `test/.../PredictionIntegrationTest.java` | `dto.RecommendRequest/Response` → `request/response` |
| `test/.../RecommendationServiceTest.java` | `dto.RecommendRequest` → `request.RecommendRequest` |

---

## Task 1: Establish baseline — all tests pass

**Files:** (read-only verification)

- [ ] **Step 1: Run the full test suite**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
mvn test -DexcludedGroups=load 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` with 0 failures. If tests fail before any changes, record which ones so you can distinguish pre-existing failures from regressions.

- [ ] **Step 2: Commit baseline note (skip if tests already pass)**

Only needed if there are pre-existing failures:
```bash
# Just note the failures; do NOT commit broken tests.
# Record the count here for reference.
```

---

## Task 2: Create `exception/` package — move three exception classes

**Files:**
- Create: `src/main/java/com/recsys/modelbased/exception/RateLimitExceededException.java`
- Create: `src/main/java/com/recsys/modelbased/exception/ServiceOverloadedException.java`
- Create: `src/main/java/com/recsys/modelbased/exception/SubmitTokenException.java`
- Modify: `src/main/java/com/recsys/modelbased/config/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/recsys/modelbased/controller/RecommendationController.java`
- Modify: `src/main/java/com/recsys/modelbased/service/SubmitTokenService.java`
- Modify: `src/test/java/com/recsys/modelbased/controller/RecommendationControllerTest.java`
- Delete: `src/main/java/com/recsys/modelbased/service/RateLimitExceededException.java`
- Delete: `src/main/java/com/recsys/modelbased/service/ServiceOverloadedException.java`
- Delete: `src/main/java/com/recsys/modelbased/service/SubmitTokenException.java`

- [ ] **Step 1: Create `exception/RateLimitExceededException.java`**

```java
package com.recsys.modelbased.exception;

public class RateLimitExceededException extends RuntimeException {

    private final int retryAfterSeconds;

    public RateLimitExceededException(int retryAfterSeconds) {
        super("request rate limit exceeded — retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

- [ ] **Step 2: Create `exception/ServiceOverloadedException.java`**

```java
package com.recsys.modelbased.exception;

public class ServiceOverloadedException extends RuntimeException {

    private final int retryAfterSeconds;

    public ServiceOverloadedException(int retryAfterSeconds) {
        super("recommendation service is overloaded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

- [ ] **Step 3: Create `exception/SubmitTokenException.java`**

```java
package com.recsys.modelbased.exception;

public class SubmitTokenException extends RuntimeException {
    public SubmitTokenException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Update `config/GlobalExceptionHandler.java` — swap 3 imports**

Replace:
```java
import com.recsys.modelbased.service.RateLimitExceededException;
import com.recsys.modelbased.service.ServiceOverloadedException;
import com.recsys.modelbased.service.SubmitTokenException;
```
With:
```java
import com.recsys.modelbased.exception.RateLimitExceededException;
import com.recsys.modelbased.exception.ServiceOverloadedException;
import com.recsys.modelbased.exception.SubmitTokenException;
```

- [ ] **Step 5: Update `controller/RecommendationController.java` — swap 2 imports**

Replace:
```java
import com.recsys.modelbased.service.RateLimitExceededException;
import com.recsys.modelbased.service.ServiceOverloadedException;
```
With:
```java
import com.recsys.modelbased.exception.RateLimitExceededException;
import com.recsys.modelbased.exception.ServiceOverloadedException;
```

- [ ] **Step 6: Update `service/SubmitTokenService.java` — add explicit import**

`SubmitTokenException` was previously in the same package (`service`), so no import was needed. After moving it to `exception`, add at the top of `SubmitTokenService.java`:

```java
import com.recsys.modelbased.exception.SubmitTokenException;
```

(Insert after the last existing import line.)

- [ ] **Step 7: Update `test/.../RecommendationControllerTest.java` — swap 1 import**

Replace:
```java
import com.recsys.modelbased.service.SubmitTokenException;
```
With:
```java
import com.recsys.modelbased.exception.SubmitTokenException;
```

- [ ] **Step 8: Delete the three old exception files**

```bash
rm src/main/java/com/recsys/modelbased/service/RateLimitExceededException.java
rm src/main/java/com/recsys/modelbased/service/ServiceOverloadedException.java
rm src/main/java/com/recsys/modelbased/service/SubmitTokenException.java
```

- [ ] **Step 9: Verify compilation and tests pass**

```bash
mvn test -DexcludedGroups=load -Dtest="RecommendationControllerTest,RecommendationServiceTest,SubmitTokenServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/recsys/modelbased/exception/ \
        src/main/java/com/recsys/modelbased/config/GlobalExceptionHandler.java \
        src/main/java/com/recsys/modelbased/controller/RecommendationController.java \
        src/main/java/com/recsys/modelbased/service/SubmitTokenService.java \
        src/test/java/com/recsys/modelbased/controller/RecommendationControllerTest.java
git rm src/main/java/com/recsys/modelbased/service/RateLimitExceededException.java \
       src/main/java/com/recsys/modelbased/service/ServiceOverloadedException.java \
       src/main/java/com/recsys/modelbased/service/SubmitTokenException.java
git commit -m "refactor: move exceptions from service/ to exception/ package"
```

---

## Task 3: Move request objects from `dto/` to `request/`

Moves `RecommendRequest` and `ModelVersionRequest`. Both already have the `request/` package siblings (`CreateKnowledgeBaseRequest`, `UpdateKnowledgeBaseRequest`), so this just adds two more files.

**Files:**
- Create: `src/main/java/com/recsys/modelbased/request/RecommendRequest.java`
- Create: `src/main/java/com/recsys/modelbased/request/ModelVersionRequest.java`
- Modify (main): `controller/RecommendationController.java`, `controller/VersionController.java`, `service/RecommendationService.java`, `service/FeatureEncoder.java`
- Modify (test): `RecommendationControllerTest`, `RecommendationEndToEndTest`, `FeatureEncoderTest`, `InferenceLoadTest`, `PredictionIntegrationTest`, `RecommendationServiceTest`
- Delete: `src/main/java/com/recsys/modelbased/dto/RecommendRequest.java`
- Delete: `src/main/java/com/recsys/modelbased/dto/ModelVersionRequest.java`

- [ ] **Step 1: Create `request/RecommendRequest.java`**

```java
package com.recsys.modelbased.request;

import jakarta.validation.constraints.*;

import java.util.ArrayList;
import java.util.List;

public class RecommendRequest {

    @NotBlank(message = "userId is required")
    @Size(max = 50, message = "userId must not exceed 50 characters")
    private String userId;

    @Min(value = 1, message = "k must be at least 1")
    @Max(value = 100, message = "k must be at most 100")
    private int k = 5;

    @Size(max = 500, message = "excludeItemIds must not exceed 500 entries")
    private List<@NotBlank(message = "item IDs in excludeItemIds must not be blank")
                 @Size(max = 50, message = "each excluded item ID must not exceed 50 characters")
                 String> excludeItemIds = new ArrayList<>();

    public RecommendRequest() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public int getK() { return k; }
    public void setK(int k) { this.k = k; }

    public List<String> getExcludeItemIds() { return excludeItemIds; }
    public void setExcludeItemIds(List<String> excludeItemIds) {
        this.excludeItemIds = excludeItemIds == null ? new ArrayList<>() : excludeItemIds;
    }
}
```

- [ ] **Step 2: Create `request/ModelVersionRequest.java`**

```java
package com.recsys.modelbased.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModelVersionRequest(
        @NotBlank(message = "variant is required")
        @Size(max = 80, message = "variant must not exceed 80 characters")
        String variant
) {
}
```

- [ ] **Step 3: Update main-source imports — RecommendRequest**

In each file below, replace `import com.recsys.modelbased.dto.RecommendRequest;` with `import com.recsys.modelbased.request.RecommendRequest;`:

- `src/main/java/com/recsys/modelbased/controller/RecommendationController.java`
- `src/main/java/com/recsys/modelbased/service/RecommendationService.java`
- `src/main/java/com/recsys/modelbased/service/FeatureEncoder.java`

- [ ] **Step 4: Update main-source imports — ModelVersionRequest**

In `src/main/java/com/recsys/modelbased/controller/VersionController.java`, replace:
```java
import com.recsys.modelbased.dto.ModelVersionRequest;
```
With:
```java
import com.recsys.modelbased.request.ModelVersionRequest;
```

- [ ] **Step 5: Update test imports — RecommendRequest**

In each test file below, replace `import com.recsys.modelbased.dto.RecommendRequest;` with `import com.recsys.modelbased.request.RecommendRequest;`:

- `src/test/java/com/recsys/modelbased/controller/RecommendationControllerTest.java`
- `src/test/java/com/recsys/modelbased/controller/RecommendationEndToEndTest.java`
- `src/test/java/com/recsys/modelbased/service/FeatureEncoderTest.java`
- `src/test/java/com/recsys/modelbased/service/InferenceLoadTest.java`
- `src/test/java/com/recsys/modelbased/service/PredictionIntegrationTest.java`
- `src/test/java/com/recsys/modelbased/service/RecommendationServiceTest.java`

- [ ] **Step 6: Delete old dto files**

```bash
rm src/main/java/com/recsys/modelbased/dto/RecommendRequest.java
rm src/main/java/com/recsys/modelbased/dto/ModelVersionRequest.java
```

- [ ] **Step 7: Verify compilation and tests pass**

```bash
mvn test -DexcludedGroups=load -Dtest="RecommendationControllerTest,RecommendationEndToEndTest,FeatureEncoderTest,RecommendationServiceTest,VersionControllerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/recsys/modelbased/request/RecommendRequest.java \
        src/main/java/com/recsys/modelbased/request/ModelVersionRequest.java \
        src/main/java/com/recsys/modelbased/controller/RecommendationController.java \
        src/main/java/com/recsys/modelbased/controller/VersionController.java \
        src/main/java/com/recsys/modelbased/service/RecommendationService.java \
        src/main/java/com/recsys/modelbased/service/FeatureEncoder.java \
        src/test/java/com/recsys/modelbased/controller/RecommendationControllerTest.java \
        src/test/java/com/recsys/modelbased/controller/RecommendationEndToEndTest.java \
        src/test/java/com/recsys/modelbased/service/FeatureEncoderTest.java \
        src/test/java/com/recsys/modelbased/service/InferenceLoadTest.java \
        src/test/java/com/recsys/modelbased/service/PredictionIntegrationTest.java \
        src/test/java/com/recsys/modelbased/service/RecommendationServiceTest.java
git rm src/main/java/com/recsys/modelbased/dto/RecommendRequest.java \
       src/main/java/com/recsys/modelbased/dto/ModelVersionRequest.java
git commit -m "refactor: move RecommendRequest and ModelVersionRequest from dto/ to request/"
```

---

## Task 4: Move response objects from `dto/` to `response/`

Moves `RecommendResponse`, `SubmitTokenResponse`, and `ModelVersionResponse`. The `response/` package already contains `CreateKnowledgeBaseResponse` and `GetKnowledgeBasesResponse`.

**Files:**
- Create: `src/main/java/com/recsys/modelbased/response/RecommendResponse.java`
- Create: `src/main/java/com/recsys/modelbased/response/SubmitTokenResponse.java`
- Create: `src/main/java/com/recsys/modelbased/response/ModelVersionResponse.java`
- Modify (main): `controller/RecommendationController.java`, `controller/VersionController.java`, `service/RecommendationService.java`, `service/ModelVersionService.java`
- Modify (test): `RecommendationControllerTest`, `RecommendationEndToEndTest`, `PredictionIntegrationTest`, `VersionControllerTest`
- Delete: `src/main/java/com/recsys/modelbased/dto/RecommendResponse.java`
- Delete: `src/main/java/com/recsys/modelbased/dto/SubmitTokenResponse.java`
- Delete: `src/main/java/com/recsys/modelbased/dto/ModelVersionResponse.java`

- [ ] **Step 1: Create `response/RecommendResponse.java`**

```java
package com.recsys.modelbased.response;

import com.recsys.modelbased.dto.ScoredItem;

import java.util.List;

public record RecommendResponse(String userId, String modelVersion, String abTestVariant, List<ScoredItem> recommendations) {}
```

- [ ] **Step 2: Create `response/SubmitTokenResponse.java`**

```java
package com.recsys.modelbased.response;

public record SubmitTokenResponse(String token, int expiresInSeconds) {}
```

- [ ] **Step 3: Create `response/ModelVersionResponse.java`**

```java
package com.recsys.modelbased.response;

import java.util.List;

public record ModelVersionResponse(
        String activeVariant,
        String previousActiveVariant,
        List<VariantVersion> variants
) {
    public record VariantVersion(
            String variant,
            String modelVersion,
            boolean ready,
            boolean active
    ) {
    }
}
```

- [ ] **Step 4: Update main-source imports — RecommendResponse**

In each file below, replace `import com.recsys.modelbased.dto.RecommendResponse;` with `import com.recsys.modelbased.response.RecommendResponse;`:

- `src/main/java/com/recsys/modelbased/controller/RecommendationController.java`
- `src/main/java/com/recsys/modelbased/service/RecommendationService.java`

- [ ] **Step 5: Update main-source imports — SubmitTokenResponse**

In `src/main/java/com/recsys/modelbased/controller/RecommendationController.java`, replace:
```java
import com.recsys.modelbased.dto.SubmitTokenResponse;
```
With:
```java
import com.recsys.modelbased.response.SubmitTokenResponse;
```

- [ ] **Step 6: Update main-source imports — ModelVersionResponse**

In each file below, replace `import com.recsys.modelbased.dto.ModelVersionResponse;` with `import com.recsys.modelbased.response.ModelVersionResponse;`:

- `src/main/java/com/recsys/modelbased/controller/VersionController.java`
- `src/main/java/com/recsys/modelbased/service/ModelVersionService.java`

- [ ] **Step 7: Update test imports**

`RecommendResponse` tests — replace `import com.recsys.modelbased.dto.RecommendResponse;` with `import com.recsys.modelbased.response.RecommendResponse;` in:

- `src/test/java/com/recsys/modelbased/controller/RecommendationControllerTest.java`
- `src/test/java/com/recsys/modelbased/controller/RecommendationEndToEndTest.java`
- `src/test/java/com/recsys/modelbased/service/PredictionIntegrationTest.java`

`ModelVersionResponse` tests — replace `import com.recsys.modelbased.dto.ModelVersionResponse;` with `import com.recsys.modelbased.response.ModelVersionResponse;` in:

- `src/test/java/com/recsys/modelbased/controller/VersionControllerTest.java`

- [ ] **Step 8: Delete old dto files**

```bash
rm src/main/java/com/recsys/modelbased/dto/RecommendResponse.java
rm src/main/java/com/recsys/modelbased/dto/SubmitTokenResponse.java
rm src/main/java/com/recsys/modelbased/dto/ModelVersionResponse.java
```

- [ ] **Step 9: Verify compilation and full test suite passes**

```bash
mvn test -DexcludedGroups=load 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/recsys/modelbased/response/RecommendResponse.java \
        src/main/java/com/recsys/modelbased/response/SubmitTokenResponse.java \
        src/main/java/com/recsys/modelbased/response/ModelVersionResponse.java \
        src/main/java/com/recsys/modelbased/controller/RecommendationController.java \
        src/main/java/com/recsys/modelbased/controller/VersionController.java \
        src/main/java/com/recsys/modelbased/service/RecommendationService.java \
        src/main/java/com/recsys/modelbased/service/ModelVersionService.java \
        src/test/java/com/recsys/modelbased/controller/RecommendationControllerTest.java \
        src/test/java/com/recsys/modelbased/controller/RecommendationEndToEndTest.java \
        src/test/java/com/recsys/modelbased/controller/VersionControllerTest.java \
        src/test/java/com/recsys/modelbased/service/PredictionIntegrationTest.java
git rm src/main/java/com/recsys/modelbased/dto/RecommendResponse.java \
       src/main/java/com/recsys/modelbased/dto/SubmitTokenResponse.java \
       src/main/java/com/recsys/modelbased/dto/ModelVersionResponse.java
git commit -m "refactor: move RecommendResponse, SubmitTokenResponse, ModelVersionResponse from dto/ to response/"
```

---

## Task 5: Add `RecommendationConverter` — extract response-building and request-normalization helpers

`RecommendationService` has two private static helpers (`response()` and `normalizedExcludeItemIds()`) that are the recommendation domain's equivalents of what `KnowledgeBaseConverter` does for the KB domain. Extracting them into a `@Component` makes the pattern consistent and keeps `RecommendationService` focused on orchestration logic.

**Files:**
- Create: `src/main/java/com/recsys/modelbased/converter/RecommendationConverter.java`
- Modify: `src/main/java/com/recsys/modelbased/service/RecommendationService.java`
- Test: `src/test/java/com/recsys/modelbased/service/RecommendationServiceTest.java` (verify no behavior change)

- [ ] **Step 1: Create `converter/RecommendationConverter.java`**

```java
package com.recsys.modelbased.converter;

import com.recsys.modelbased.dto.ScoredItem;
import com.recsys.modelbased.request.RecommendRequest;
import com.recsys.modelbased.response.RecommendResponse;
import com.recsys.modelbased.service.ABTestService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TreeSet;

@Component
public class RecommendationConverter {

    public RecommendResponse toResponse(
            RecommendRequest request,
            String modelVersion,
            ABTestService.Assignment assignment,
            List<ScoredItem> items
    ) {
        return new RecommendResponse(request.getUserId(), modelVersion, assignment.variant(), items);
    }

    public List<String> normalizedExcludeItemIds(RecommendRequest request) {
        if (request.getExcludeItemIds() == null || request.getExcludeItemIds().isEmpty()) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(request.getExcludeItemIds()));
    }
}
```

- [ ] **Step 2: Update `RecommendationService` to inject and use the converter**

Replace the four-argument `@Autowired` constructor to accept `RecommendationConverter`:

```java
@Autowired
public RecommendationService(
        ModelRuntimeProvider modelRuntimeProvider,
        ABTestService abTestService,
        RecommendationCacheProperties cacheProperties,
        FeatureFlagService featureFlagService,
        RecommendationConverter converter
) {
    this.modelRuntimeProvider = modelRuntimeProvider;
    this.abTestService = abTestService;
    this.cache = new RecommendationCache(cacheProperties);
    this.featureFlagService = featureFlagService;
    this.converter = converter;
}
```

Add field:
```java
private final RecommendationConverter converter;
```

Update the two-arg and three-arg convenience constructors to pass a no-op converter (for tests that construct the service directly):

```java
public RecommendationService(
        ModelRuntimeProvider modelRuntimeProvider,
        ABTestService abTestService
) {
    this(modelRuntimeProvider, abTestService, new RecommendationCacheProperties());
}

public RecommendationService(
        ModelRuntimeProvider modelRuntimeProvider,
        ABTestService abTestService,
        RecommendationCacheProperties cacheProperties
) {
    this(modelRuntimeProvider, abTestService, cacheProperties, NOOP_FLAGS);
}

public RecommendationService(
        ModelRuntimeProvider modelRuntimeProvider,
        ABTestService abTestService,
        RecommendationCacheProperties cacheProperties,
        FeatureFlagService featureFlagService
) {
    this(modelRuntimeProvider, abTestService, cacheProperties, featureFlagService, new RecommendationConverter());
}
```

Replace the two call sites in `RecommendationService` that used the old static helpers:

Before:
```java
var cacheKey = new RecommendationCache.RecommendationKey(
        request.getUserId(), request.getK(), excludedItemIds, assignment.variant(), modelVersion);
```
The `excludedItemIds` variable is now computed via `converter.normalizedExcludeItemIds(request)` — replace all occurrences of:
```java
List<String> excludedItemIds = normalizedExcludeItemIds(request);
```
With:
```java
List<String> excludedItemIds = converter.normalizedExcludeItemIds(request);
```

Replace the call to the static `response()` helper:
```java
return response(request, modelVersion, assignment, items);
```
With:
```java
return converter.toResponse(request, modelVersion, assignment, items);
```

Delete the two private static helpers `response()` and `normalizedExcludeItemIds()` from `RecommendationService`.

Add import at the top of `RecommendationService`:
```java
import com.recsys.modelbased.converter.RecommendationConverter;
```

- [ ] **Step 3: Verify tests still pass**

```bash
mvn test -DexcludedGroups=load -Dtest="RecommendationServiceTest,RecommendationControllerTest,RecommendationEndToEndTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` — no behavior change, only structural

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/recsys/modelbased/converter/RecommendationConverter.java \
        src/main/java/com/recsys/modelbased/service/RecommendationService.java
git commit -m "refactor: extract RecommendationConverter from RecommendationService statics"
```

---

## Task 6: Final verification

- [ ] **Step 1: Run the full test suite**

```bash
mvn test -DexcludedGroups=load 2>&1 | tail -30
```

Expected: `BUILD SUCCESS` — same pass/fail count as Task 1 baseline

- [ ] **Step 2: Verify `dto/` contains only the four expected files**

```bash
ls src/main/java/com/recsys/modelbased/dto/
```

Expected output (exactly these four files):
```
ApiError.java
ApiResponse.java
KnowledgeBaseDTO.java
ScoredItem.java
```

- [ ] **Step 3: Verify `exception/` contains three files**

```bash
ls src/main/java/com/recsys/modelbased/exception/
```

Expected:
```
RateLimitExceededException.java
ServiceOverloadedException.java
SubmitTokenException.java
```

- [ ] **Step 4: Verify no stale `dto.*` imports for moved classes remain**

```bash
grep -rn "modelbased\.dto\.\(RecommendRequest\|RecommendResponse\|SubmitTokenResponse\|ModelVersionRequest\|ModelVersionResponse\)" src/ --include="*.java"
```

Expected: no output

- [ ] **Step 5: Verify no stale `service.*` imports for moved exceptions remain**

```bash
grep -rn "modelbased\.service\.\(RateLimitExceededException\|ServiceOverloadedException\|SubmitTokenException\)" src/ --include="*.java"
```

Expected: no output

---

## Final structure

```
modelbased/
├── ModelApplication.java
├── config/         ABTestConfig, GlobalExceptionHandler, HealthProperties,
│                   RecommendationCacheProperties, SubmitTokenProperties
├── controller/     HealthController, KnowledgeBaseController,
│                   RecommendationController, VersionController
├── converter/      KnowledgeBaseConverter, RecommendationConverter
├── dto/            ApiError, ApiResponse, KnowledgeBaseDTO, ScoredItem
├── entity/         KnowledgeBase
├── exception/      RateLimitExceededException, ServiceOverloadedException,
│                   SubmitTokenException
├── request/        CreateKnowledgeBaseRequest, ModelVersionRequest,
│                   RecommendRequest, UpdateKnowledgeBaseRequest
├── response/       CreateKnowledgeBaseResponse, GetKnowledgeBasesResponse,
│                   ModelVersionResponse, RecommendResponse, SubmitTokenResponse
├── service/        ABTestService, CandidateSelectionService, FeatureEncoder,
│                   GcEventTracker, GracefulShutdownSupport, InferenceMetricsService,
│                   JvmMemoryMonitor, KnowledgeBaseFacadeService, LoadShedder,
│                   ModelArtifactLocator, ModelArtifactService, ModelRateLimiter,
│                   ModelRuntime, ModelRuntimeProvider, ModelVariants,
│                   ModelVersionService, RankingService, RecommendationCache,
│                   RecommendationService, RetrievalService, ScoredItems,
│                   SubmitTokenService, UserTowerInferenceService
└── vo/             KnowledgeBaseVO
```
