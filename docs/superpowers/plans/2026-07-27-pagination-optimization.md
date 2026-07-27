# Pagination Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver secure, bounded, forward-only live-keyset pagination with one contract across model and online recommendation serving, while preserving catalog keyset behavior and fixing generic MySQL lookahead.

**Architecture:** A signed `RecommendationCursorCodec` owns recommendation cursor wire security and migration; a pure `CursorPaginationService` owns tuple seeking and lookahead; a shared `RecommendationPaginationCoordinator` owns query binding, response invariants, and metrics. Model and online serving supply bounded ranked candidate windows to the coordinator. Catalog keeps its specialized decimal cursor, while `MySqlClient.queryPage` gains explicit `pageSize + 1` lookahead semantics.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Mockito, Micrometer, Armeria, HMAC-SHA256, Base64 URL encoding.

## Global Constraints

- Recommendation pagination is forward-only and stateless; do not add snapshots or server-side sessions.
- Recommendation ordering is exactly `(score DESC, itemId ASC)`.
- Every newly issued recommendation cursor is HMAC-SHA256 authenticated and bound to user plus normalized exclusions.
- Cursor payloads are not encrypted.
- Active and optional previous keys must each contain at least 32 UTF-8 bytes.
- Existing unsigned `v2` cursors are accepted only behind an explicit compatibility flag and are never reissued.
- `limit` is not part of the query fingerprint; changing it between pages remains valid.
- Invalid cursors fail before candidate recall and map to a generic `400`.
- `nextCursor != null` if and only if `hasMore == true`.
- Candidate processing is capped by configuration; budget exhaustion must not emit a speculative cursor.
- Public APIs do not add offset pagination.
- The catalog's signed `(popularity_score DESC, id DESC)` behavior and index contracts remain unchanged.
- Do not log raw cursors, signing keys, user IDs, item IDs, or query fingerprints.

## File and Responsibility Map

### New production files

- `src/main/java/com/recsys/application/pagination/RecommendationPaginationConfig.java` — validated environment-backed cursor lifetime, key rotation, legacy migration, and candidate budget.
- `src/main/java/com/recsys/application/pagination/RecommendationQueryFingerprint.java` — deterministic SHA-256 fingerprint of pagination-affecting query inputs.
- `src/main/java/com/recsys/application/pagination/RecommendationCursorCodec.java` — signed cursor encoding/decoding and strict legacy decoding.
- `src/main/java/com/recsys/application/pagination/RecommendationPaginationMetrics.java` — bounded Micrometer counters with fixed safe reason labels.
- `src/main/java/com/recsys/application/pagination/RecommendationPaginationCoordinator.java` — query validation, pure page invocation, signed cursor issuance, response invariants, and metrics.
- `src/main/java/com/recsys/application/pagination/RecommendationPaginationRuntime.java` — one environment-backed factory returning the shared coordinator and candidate ceiling for server wiring.

### Modified production files

- `src/main/java/com/recsys/application/pagination/RankedListCursor.java` — internal validated tuple only; remove public wire ownership after migration.
- `src/main/java/com/recsys/application/pagination/Page.java` — include internal next position and `hasMore`.
- `src/main/java/com/recsys/application/pagination/CursorPaginationService.java` — full-tuple seek, ordering validation, and lookahead.
- `src/main/java/com/recsys/domain/recommendation/RecommendationResult.java` — add `boolean hasMore` and enforce cursor agreement.
- `src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java` — bounded ranked-window supplier for the shared coordinator.
- `src/main/java/com/recsys/application/online/OnlineBlendingPipeline.java` — honor cursors/exclusions and use the shared coordinator.
- `src/main/java/com/recsys/infrastructure/persistence/MySqlClient.java` — trim `pageSize + 1` and emit exact cursor metadata.
- `src/main/java/com/recsys/api/serving/RecSysServer.java` — construct and inject recommendation pagination runtime.
- `src/main/java/com/recsys/api/online/OnlinePredictionServer.java` — construct and inject the same pagination contract.
- `CONFIG_GUIDE.md`, `README.md`, `docs/system_design/19_Pagination.md` — runtime variables and current-state rollout documentation.

### Test files

- Create `src/test/java/com/recsys/application/pagination/RecommendationPaginationConfigTest.java`.
- Create `src/test/java/com/recsys/application/pagination/RecommendationQueryFingerprintTest.java`.
- Create `src/test/java/com/recsys/application/pagination/RecommendationCursorCodecTest.java`.
- Create `src/test/java/com/recsys/application/pagination/RecommendationPaginationMetricsTest.java`.
- Create `src/test/java/com/recsys/application/pagination/RecommendationPaginationCoordinatorTest.java`.
- Modify `src/test/java/com/recsys/application/pagination/CursorPaginationServiceTest.java`.
- Modify `src/test/java/com/recsys/application/recommendation/RecommendationOrchestratorTest.java`.
- Modify `src/test/java/com/recsys/application/online/OnlineBlendingPipelineTest.java`.
- Modify `src/test/java/com/recsys/infrastructure/persistence/MySqlClientTest.java`.
- Modify cross-path and HTTP tests that construct or assert `RecommendationResult`.

---

### Task 1: Validated Pagination Configuration

**Files:**
- Create: `src/main/java/com/recsys/application/pagination/RecommendationPaginationConfig.java`
- Create: `src/test/java/com/recsys/application/pagination/RecommendationPaginationConfigTest.java`

**Interfaces:**
- Consumes: `com.recsys.config.EnvVars.EnvReader`
- Produces:
  - `RecommendationPaginationConfig.fromEnvironment()`
  - `RecommendationPaginationConfig.fromEnvironment(EnvVars.EnvReader env)`
  - Accessors `activeSigningKey()`, `previousVerificationKey()`, `maxAge()`, `acceptLegacy()`, `maxCandidates()`

- [ ] **Step 1: Write failing configuration tests**

```java
class RecommendationPaginationConfigTest {
    @Test
    void readsValidatedConfiguration() {
        Map<String, String> env = Map.of(
                "RECOMMENDATION_CURSOR_SIGNING_KEY", "a".repeat(32),
                "RECOMMENDATION_CURSOR_PREVIOUS_KEY", "b".repeat(32),
                "RECOMMENDATION_CURSOR_MAX_AGE_SECONDS", "900",
                "RECOMMENDATION_CURSOR_ACCEPT_LEGACY", "true",
                "RECOMMENDATION_PAGINATION_MAX_CANDIDATES", "500");

        var config = RecommendationPaginationConfig.fromEnvironment(env::get);

        assertThat(config.activeSigningKey()).isEqualTo("a".repeat(32));
        assertThat(config.previousVerificationKey()).isEqualTo("b".repeat(32));
        assertThat(config.maxAge()).isEqualTo(Duration.ofMinutes(15));
        assertThat(config.acceptLegacy()).isTrue();
        assertThat(config.maxCandidates()).isEqualTo(500);
    }

    @Test
    void rejectsMissingOrShortKeysAndOutOfRangeNumbers() {
        assertThatThrownBy(() -> RecommendationPaginationConfig.fromEnvironment(name -> null))
                .hasMessageContaining("RECOMMENDATION_CURSOR_SIGNING_KEY");
        assertThatThrownBy(() -> RecommendationPaginationConfig.fromEnvironment(
                name -> name.equals("RECOMMENDATION_CURSOR_SIGNING_KEY") ? "short" : null))
                .hasMessageContaining("32 UTF-8 bytes");
    }
}
```

Also test: blank previous key becomes null; previous key shorter than 32 bytes fails; maximum age must be `1..86400`; maximum candidates must be `101..10000`; invalid booleans fail rather than silently default.

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```bash
mvn -Dtest=RecommendationPaginationConfigTest test
```

Expected: compilation failure because `RecommendationPaginationConfig` does not exist.

- [ ] **Step 3: Implement the immutable validated configuration**

```java
public record RecommendationPaginationConfig(
        String activeSigningKey,
        String previousVerificationKey,
        Duration maxAge,
        boolean acceptLegacy,
        int maxCandidates
) {
    public static RecommendationPaginationConfig fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    static RecommendationPaginationConfig fromEnvironment(EnvVars.EnvReader env) {
        String active = requireKey(env.get("RECOMMENDATION_CURSOR_SIGNING_KEY"),
                "RECOMMENDATION_CURSOR_SIGNING_KEY");
        String previous = optionalKey(env.get("RECOMMENDATION_CURSOR_PREVIOUS_KEY"),
                "RECOMMENDATION_CURSOR_PREVIOUS_KEY");
        long maxAgeSeconds = EnvVars.readLong(env,
                "RECOMMENDATION_CURSOR_MAX_AGE_SECONDS", 900);
        int maxCandidates = EnvVars.readInt(env,
                "RECOMMENDATION_PAGINATION_MAX_CANDIDATES", 500);
        boolean acceptLegacy = strictBoolean(env,
                "RECOMMENDATION_CURSOR_ACCEPT_LEGACY", true);
        if (maxAgeSeconds < 1 || maxAgeSeconds > 86_400) {
            throw new IllegalArgumentException(
                    "RECOMMENDATION_CURSOR_MAX_AGE_SECONDS must be between 1 and 86400");
        }
        if (maxCandidates < 101 || maxCandidates > 10_000) {
            throw new IllegalArgumentException(
                    "RECOMMENDATION_PAGINATION_MAX_CANDIDATES must be between 101 and 10000");
        }
        return new RecommendationPaginationConfig(active, previous,
                Duration.ofSeconds(maxAgeSeconds), acceptLegacy, maxCandidates);
    }
}
```

Implement strict case-insensitive `true`/`false` parsing locally; do not reuse the permissive `EnvVars.readBool`.

- [ ] **Step 4: Run configuration tests**

Run:

```bash
mvn -Dtest=RecommendationPaginationConfigTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/pagination/RecommendationPaginationConfig.java \
  src/test/java/com/recsys/application/pagination/RecommendationPaginationConfigTest.java
git commit -m "feat(pagination): add validated recommendation config"
```

---

### Task 2: Query Fingerprint and Signed Cursor Codec

**Files:**
- Create: `src/main/java/com/recsys/application/pagination/RecommendationQueryFingerprint.java`
- Create: `src/main/java/com/recsys/application/pagination/RecommendationCursorCodec.java`
- Create: `src/test/java/com/recsys/application/pagination/RecommendationQueryFingerprintTest.java`
- Create: `src/test/java/com/recsys/application/pagination/RecommendationCursorCodecTest.java`
- Modify: `src/main/java/com/recsys/application/pagination/RankedListCursor.java`

**Interfaces:**
- Consumes:
  - `RecommendationPaginationConfig`
  - `RecommendationQuery`
  - `Clock`
- Produces:
  - `RecommendationQueryFingerprint.of(RecommendationQuery query): String`
  - `RecommendationCursorCodec(config, clock)`
  - `RecommendationCursorCodec.encode(RecommendationQuery query, RankedListCursor position): String`
  - `RecommendationCursorCodec.decode(RecommendationQuery query, String token): DecodedCursor`
  - `DecodedCursor(RankedListCursor position, boolean legacy, boolean previousKey)`
  - `InvalidCursorException` with a package-private fixed `CursorFailureReason reason()`

- [ ] **Step 1: Write failing fingerprint tests**

```java
@Test
void exclusionsAreCanonicalAndLimitDoesNotAffectFingerprint() {
    var a = new RecommendationQuery("u1", 10, Set.of("3", "1"), null);
    var b = new RecommendationQuery("u1", 50, Set.of("1", "3"), null);
    assertThat(RecommendationQueryFingerprint.of(a))
            .isEqualTo(RecommendationQueryFingerprint.of(b));
}

@Test
void userAndExclusionsAffectFingerprint() {
    assertThat(RecommendationQueryFingerprint.of(query("u1", Set.of("1"))))
            .isNotEqualTo(RecommendationQueryFingerprint.of(query("u2", Set.of("1"))))
            .isNotEqualTo(RecommendationQueryFingerprint.of(query("u1", Set.of("2"))));
}
```

Use a length-prefixed canonical byte representation, not delimiter concatenation:
`version`, UTF-8 byte length + user bytes, exclusion count, then each sorted exclusion byte length + bytes. Return lowercase SHA-256 hex.

- [ ] **Step 2: Write failing codec tests**

Cover signed round trip, tampering, wrong user, changed exclusions, changed limit, expiry at `issuedAt + maxAge`, active/previous key verification, token length > 2048, malformed Base64, malformed UTF-8, unsupported version, blank/oversized item ID, and `NaN`/infinities.

```java
@Test
void signedCursorIsBoundToUserAndExclusionsButNotLimit() {
    String token = codec.encode(
            new RecommendationQuery("u1", 10, Set.of("seen"), null),
            new RankedListCursor(0.75, "42"));

    assertThat(codec.decode(
            new RecommendationQuery("u1", 50, Set.of("seen"), token), token).position())
            .isEqualTo(new RankedListCursor(0.75, "42"));
    assertThatThrownBy(() -> codec.decode(
            new RecommendationQuery("u2", 10, Set.of("seen"), token), token))
            .isInstanceOf(RecommendationCursorCodec.InvalidCursorException.class)
            .hasMessage("Invalid recommendation cursor");
}

@Test
void legacyCursorIsAcceptedOnlyWhenEnabledAndMarkedForUpgrade() {
    String legacy = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("v2:0.75:42".getBytes(StandardCharsets.UTF_8));
    assertThat(codec.decode(query, legacy))
            .isEqualTo(new DecodedCursor(new RankedListCursor(0.75, "42"), true, false));
}
```

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```bash
mvn -Dtest=RecommendationQueryFingerprintTest,RecommendationCursorCodecTest test
```

Expected: compilation failure for missing types and methods.

- [ ] **Step 4: Implement the fingerprint and internal tuple validation**

`RankedListCursor` must reject non-start positions with a non-finite score, blank item ID, or item ID longer than 512 Unicode code points. Keep `START` as the only sentinel and remove `encode/decode` only after all callers migrate in later tasks.

```java
public record RankedListCursor(double score, String itemId) {
    public static final RankedListCursor START = new RankedListCursor(Double.NaN, null);

    public RankedListCursor {
        if (itemId != null) {
            if (!Double.isFinite(score)) throw new IllegalArgumentException("score must be finite");
            if (itemId.isBlank()) throw new IllegalArgumentException("itemId must not be blank");
            if (itemId.codePointCount(0, itemId.length()) > 512) {
                throw new IllegalArgumentException("itemId is too long");
            }
        }
    }
}
```

- [ ] **Step 5: Implement the signed version-3 codec**

Use payload fields separated by newlines only after Base64 URL encoding every free-text field:

```text
3
<issuedAtEpochSecond>
<base64url(userId UTF-8)>
<queryFingerprintHex>
<Double.toHexString(score)>
<base64url(itemId UTF-8)>
```

Return `<base64url(payload)>.<base64url(hmac)>`. Verify with `MessageDigest.isEqual`. Decode UTF-8 through a decoder configured with `CodingErrorAction.REPORT`. Try the active key first, then the previous key. Enforce the fixed 2048-character limit before substring or Base64 work.

Catch all parsing failures and expose only:

```java
public static final class InvalidCursorException extends IllegalArgumentException {
    private final CursorFailureReason reason;

    InvalidCursorException(CursorFailureReason reason) {
        super("Invalid recommendation cursor");
        this.reason = reason;
    }

    CursorFailureReason reason() { return reason; }
}
```

For legacy tokens, port the old `v2` parser into a private strict decoder gated by `config.acceptLegacy()`. Reject legacy score strings unless `Double.parseDouble` returns a finite value.
Use only the fixed reasons `MALFORMED`, `SIGNATURE`, `EXPIRED`,
`QUERY_MISMATCH`, `UNSUPPORTED`, and `LEGACY_DISABLED`; the reason is available
to coordinator metrics inside the package but never included in the public
message.

- [ ] **Step 6: Run codec and existing cursor tests**

Run:

```bash
mvn -Dtest=RecommendationQueryFingerprintTest,RecommendationCursorCodecTest,CursorPaginationServiceTest test
```

Expected: PASS while temporary old `RankedListCursor.encode/decode` compatibility remains available.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/pagination/RankedListCursor.java \
  src/main/java/com/recsys/application/pagination/RecommendationQueryFingerprint.java \
  src/main/java/com/recsys/application/pagination/RecommendationCursorCodec.java \
  src/test/java/com/recsys/application/pagination/RecommendationQueryFingerprintTest.java \
  src/test/java/com/recsys/application/pagination/RecommendationCursorCodecTest.java
git commit -m "feat(pagination): add signed query-bound cursors"
```

---

### Task 3: Pure Tuple Seek and Exact Lookahead

**Files:**
- Modify: `src/main/java/com/recsys/application/pagination/Page.java`
- Modify: `src/main/java/com/recsys/application/pagination/CursorPaginationService.java`
- Modify: `src/test/java/com/recsys/application/pagination/CursorPaginationServiceTest.java`

**Interfaces:**
- Consumes: `RankedListCursor`
- Produces:
  - `Page<T>(List<T> items, RankedListCursor nextPosition, boolean hasMore)`
  - `CursorPaginationService.page(List<T>, RankedListCursor, int, ToDoubleFunction<T>, Function<T,String>)`

- [ ] **Step 1: Replace cursor-string tests with tuple and lookahead tests**

Add tests for:

```java
@Test
void exactTerminalPageHasNoNextPosition() {
    Page<RankedMovie> page = page(List.of(m("a", .9), m("b", .8)),
            RankedListCursor.START, 2);
    assertThat(ids(page)).containsExactly("a", "b");
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextPosition()).isNull();
}

@Test
void lookaheadProducesPositionFromLastReturnedItem() {
    Page<RankedMovie> page = page(List.of(m("a", .9), m("b", .8), m("c", .7)),
            RankedListCursor.START, 2);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextPosition()).isEqualTo(new RankedListCursor(.8, "b"));
}

@Test
void changedAnchorScoreUsesFullTupleInsteadOfIdFastPath() {
    Page<RankedMovie> page = page(
            List.of(m("a", .9), m("b", .6), m("c", .5)),
            new RankedListCursor(.8, "b"), 2);
    assertThat(ids(page)).containsExactly("b", "c");
}
```

Also test tied IDs, inserted items before/after anchor, removed anchor, invalid limit, duplicate tuple, and unordered input.

- [ ] **Step 2: Run the test and verify failure**

Run:

```bash
mvn -Dtest=CursorPaginationServiceTest test
```

Expected: compilation/assertion failures because `Page` and `page` still use raw cursor strings.

- [ ] **Step 3: Implement immutable page invariants**

```java
public record Page<T>(List<T> items, RankedListCursor nextPosition, boolean hasMore) {
    public Page {
        items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
        if (hasMore != (nextPosition != null)) {
            throw new IllegalArgumentException("hasMore and nextPosition must agree");
        }
    }
}
```

- [ ] **Step 4: Implement strict tuple seek and lookahead**

Validate every score is finite, every ID is nonblank, and adjacent tuples are strictly ordered. Seek only by:

```java
private static boolean isAfter(double score, String id, RankedListCursor anchor) {
    return score < anchor.score()
            || (Double.compare(score, anchor.score()) == 0
                && id.compareTo(anchor.itemId()) > 0);
}
```

After locating the first qualifying item, calculate `available = rankedItems.size() - start`, `hasMore = available > limit`, and return only the first `limit`. The next position is the last returned tuple only when `hasMore`.

- [ ] **Step 5: Run pagination tests**

Run:

```bash
mvn -Dtest=CursorPaginationServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/application/pagination/Page.java \
  src/main/java/com/recsys/application/pagination/CursorPaginationService.java \
  src/test/java/com/recsys/application/pagination/CursorPaginationServiceTest.java
git commit -m "refactor(pagination): use strict tuple seek and lookahead"
```

---

### Task 4: Shared Coordinator, Response Invariants, and Metrics

**Files:**
- Create: `src/main/java/com/recsys/application/pagination/RecommendationPaginationMetrics.java`
- Create: `src/main/java/com/recsys/application/pagination/RecommendationPaginationCoordinator.java`
- Create: `src/test/java/com/recsys/application/pagination/RecommendationPaginationMetricsTest.java`
- Create: `src/test/java/com/recsys/application/pagination/RecommendationPaginationCoordinatorTest.java`
- Modify: `src/main/java/com/recsys/domain/recommendation/RecommendationResult.java`

**Interfaces:**
- Consumes: `RecommendationCursorCodec`, `CursorPaginationService`, `RecommendationPaginationMetrics`
- Produces:
  - `RecommendationPaginationCoordinator.page(query, orderedItems, sourceTruncated): RecommendationPage`
  - `RecommendationPaginationCoordinator.decode(query): DecodedRequest`
  - `RecommendationPaginationCoordinator.page(decodedRequest, orderedItems, sourceTruncated): RecommendationPage`
  - `RecommendationPaginationCoordinator.page(query, orderedItems, sourceTruncated): RecommendationPage`
  - `RecommendationPage(List<RankedMovie> items, String nextCursor, boolean hasMore, boolean legacyCursor, boolean budgetExhausted)`
  - `RecommendationResult(userId, items, nextCursor, hasMore, trace)`

- [ ] **Step 1: Write failing response-invariant and coordinator tests**

```java
@Test
void resultRequiresHasMoreAndCursorToAgree() {
    assertThatThrownBy(() -> new RecommendationResult(
            "u1", List.of(), null, true, Map.of()))
            .hasMessageContaining("hasMore and nextCursor");
}

@Test
void validatesCursorBeforeTouchingRankedItemsAndUpgradesLegacy() {
    String legacy = legacyCursor(.8, "b");
    RecommendationQuery query = new RecommendationQuery("u1", 2, Set.of(), legacy);
    RecommendationPage page = coordinator.page(query,
            List.of(m("a", .9), m("b", .8), m("c", .7), m("d", .6)), false);
    assertThat(page.items()).extracting(RankedMovie::itemId).containsExactly("c", "d");
    assertThat(page.legacyCursor()).isTrue();
    assertThat(page.nextCursor()).isNull();
}
```

Add a test where a tampered cursor is supplied with a list implementation whose iterator/get methods throw; decoding must fail before the list is accessed.

- [ ] **Step 2: Write failing bounded-metrics tests**

Use `SimpleMeterRegistry` and assert fixed counters:

```java
metrics.cursorRejected(CursorFailureReason.SIGNATURE);
metrics.legacyAccepted();
metrics.pageReturned(true);
metrics.budgetExhausted();

assertThat(registry.get("recsys.pagination.cursor.rejected")
        .tag("reason", "signature").counter().count()).isEqualTo(1.0);
```

Define a fixed enum only: `MALFORMED`, `SIGNATURE`, `EXPIRED`, `QUERY_MISMATCH`, `UNSUPPORTED`, `LEGACY_DISABLED`. Never use exception text as a tag.

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```bash
mvn -Dtest=RecommendationPaginationMetricsTest,RecommendationPaginationCoordinatorTest test
```

Expected: compilation failure for missing coordinator and metrics.

- [ ] **Step 4: Add `hasMore` to `RecommendationResult`**

```java
public record RecommendationResult(
        String userId,
        List<RankedMovie> items,
        String nextCursor,
        boolean hasMore,
        Map<String, String> trace
) {
    public RecommendationResult {
        // existing user/items/trace normalization
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor.trim();
        if (hasMore != (nextCursor != null)) {
            throw new IllegalArgumentException("hasMore and nextCursor must agree");
        }
    }
}
```

Update compilation-only constructor sites in tests with `nextCursor != null`; behavioral adaptations happen in Tasks 5 and 6.

- [ ] **Step 5: Implement coordinator and metrics**

```java
public DecodedRequest decode(RecommendationQuery query) {
    DecodedCursor decoded = query.cursor() == null
            ? new DecodedCursor(RankedListCursor.START, false, false)
            : codec.decode(query, query.cursor());
    return new DecodedRequest(query, decoded);
}

public RecommendationPage page(
        DecodedRequest request,
        List<RankedMovie> orderedItems,
        boolean sourceTruncated
) {
    RecommendationQuery query = request.query();
    DecodedCursor decoded = request.cursor();
    Page<RankedMovie> page = pagination.page(
            orderedItems, decoded.position(), query.limit(),
            RankedMovie::score, RankedMovie::itemId);
    String next = page.hasMore() ? codec.encode(query, page.nextPosition()) : null;
    if (decoded.legacy()) metrics.legacyAccepted();
    if (decoded.previousKey()) metrics.previousKeyVerified();
    boolean budgetExhausted = sourceTruncated && !page.hasMore();
    if (budgetExhausted) metrics.budgetExhausted();
    metrics.pageReturned(!page.hasMore());
    return new RecommendationPage(page.items(), next, page.hasMore(),
            decoded.legacy(), budgetExhausted);
}
```

Classify codec failures internally without exposing the reason in the public exception. Do not catch arbitrary application failures as cursor failures.
Keep `page(query, items, sourceTruncated)` as a convenience wrapper that calls
`decode(query)` first; serving paths use the split methods so invalid cursors fail
before source work.

- [ ] **Step 6: Run coordinator tests**

Run:

```bash
mvn -Dtest=RecommendationPaginationMetricsTest,RecommendationPaginationCoordinatorTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/application/pagination/RecommendationPaginationMetrics.java \
  src/main/java/com/recsys/application/pagination/RecommendationPaginationCoordinator.java \
  src/main/java/com/recsys/domain/recommendation/RecommendationResult.java \
  src/test/java/com/recsys/application/pagination/RecommendationPaginationMetricsTest.java \
  src/test/java/com/recsys/application/pagination/RecommendationPaginationCoordinatorTest.java \
  src/test/java
git commit -m "feat(pagination): add shared recommendation coordinator"
```

Before staging, inspect `git status --short` and stage only constructor migrations caused by this task.

---

### Task 5: Adopt the Coordinator in Model Serving

**Files:**
- Modify: `src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java`
- Modify: `src/test/java/com/recsys/application/recommendation/RecommendationOrchestratorTest.java`
- Modify: `src/test/java/com/recsys/application/recommendation/RecommendationOrchestratorDegradedTest.java`
- Modify: `src/test/java/com/recsys/application/recommendation/V2CrossPathLoadTest.java`
- Modify: `src/test/java/com/recsys/api/serving/EmbeddingRecallLoadTest.java`

**Interfaces:**
- Consumes:
  - `RecommendationPaginationCoordinator`
  - `RecommendationPaginationConfig.maxCandidates()`
- Produces:
  - `RecommendationOrchestrator(..., RecommendationPaginationCoordinator pagination, int maxCandidates)`

- [ ] **Step 1: Write failing bounded-recall and exact-terminal tests**

Capture the recall limit and assert:

```java
@Test
void recallIsBoundedAndTerminalMetadataIsExact() {
    RecommendationResult result = orchestrator.recommend(
            new RecommendationQuery("u1", 2, Set.of(), null));
    assertThat(capturedRecallLimit.get()).isEqualTo(500);
    assertThat(result.hasMore()).isFalse();
    assertThat(result.nextCursor()).isNull();
}
```

Add a two-page test using a signed cursor. Assert cursor validation failure prevents `recallDetailed` invocation. Add a test that hydration receives only returned page items.

- [ ] **Step 2: Run model-serving tests and verify failure**

Run:

```bash
mvn -Dtest=RecommendationOrchestratorTest,RecommendationOrchestratorDegradedTest test
```

Expected: constructor or assertion failure because the orchestrator still owns raw cursor pagination.

- [ ] **Step 3: Refactor orchestration**

Replace `CursorPaginationService` and `recallMultiplier` fields with the coordinator and `maxCandidates`.

For a stateless live feed, request the configured bounded candidate window:

```java
DecodedRequest decoded = pagination.decode(query);
int windowLimit = maxCandidates;
RecallResult recall = recallService.recallDetailed(query, windowLimit);
List<RankedMovie> ranked = ranker.rank(query, recall.candidates(), windowLimit);
boolean sourceTruncated = ranked.size() == maxCandidates;
RecommendationPage page = pagination.page(decoded, ranked, sourceTruncated);
List<RankedMovie> hydrated = hydrator.hydrate(query, page.items());
return new RecommendationResult(query.userId(), hydrated,
        page.nextCursor(), page.hasMore(), trace);
```

Validate `maxCandidates >= 101` in configuration, while query limit remains at
most 100. If `page.budgetExhausted()` is true, add trace
`paginationBudgetExhausted=true`. The coordinator owns the corresponding metric.

- [ ] **Step 4: Run model and load tests**

Run:

```bash
mvn -Dtest=RecommendationOrchestratorTest,RecommendationOrchestratorDegradedTest,V2CrossPathLoadTest,EmbeddingRecallLoadTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/recommendation/RecommendationOrchestrator.java \
  src/test/java/com/recsys/application/recommendation \
  src/test/java/com/recsys/api/serving/EmbeddingRecallLoadTest.java
git commit -m "feat(pagination): adopt signed paging in model serving"
```

---

### Task 6: Adopt the Same Contract in Online Serving

**Files:**
- Modify: `src/main/java/com/recsys/application/online/OnlineBlendingPipeline.java`
- Modify: `src/main/java/com/recsys/application/online/OnlineRecommendationService.java`
- Modify: `src/test/java/com/recsys/application/online/OnlineBlendingPipelineTest.java`
- Modify: `src/test/java/com/recsys/application/online/OnlineRecommendationServiceTest.java`
- Modify: `src/test/java/com/recsys/api/online/OnlinePredictionServerIntegrationTest.java`
- Modify: `src/test/java/com/recsys/api/rest/ModelV2RecommendIntegrationTest.java`
- Modify: `src/test/java/com/recsys/api/rest/SequentialStubIntegrationTest.java`

**Interfaces:**
- Consumes:
  - `RecommendationPaginationCoordinator`
  - `RecommendationPaginationConfig.maxCandidates()`
  - `OnlineRecommendationService.recommend(OnlineRecommendationRequest)`
- Produces:
  - `OnlineBlendingPipeline(OnlineRecommendationService, RecommendationPaginationCoordinator, int maxCandidates)`

- [ ] **Step 1: Write failing online cursor and exclusion tests**

Mock `OnlineRecommendationService` to return a deterministic movie window. Assert:

```java
@Test
void honorsExclusionsAndContinuesWithSignedCursor() {
    RecommendationResult first = pipeline.recommend(
            new RecommendationQuery("7", 2, Set.of("1"), null));
    RecommendationResult second = pipeline.recommend(
            new RecommendationQuery("7", 2, Set.of("1"), first.nextCursor()));

    assertThat(first.items()).extracting(RankedMovie::itemId).containsExactly("2", "3");
    assertThat(first.hasMore()).isTrue();
    assertThat(second.items()).extracting(RankedMovie::itemId).containsExactly("4");
    assertThat(second.hasMore()).isFalse();
}
```

Capture `OnlineRecommendationRequest` and assert `k == maxCandidates`. Add an invalid-cursor test that verifies the service is never called.
Add a service test proving `k=500` passes a recall limit of 500 instead of the
current hard-coded ceiling of 100.

- [ ] **Step 2: Run online tests and verify failure**

Run:

```bash
mvn -Dtest=OnlineBlendingPipelineTest,OnlinePredictionServerIntegrationTest test
```

Expected: assertion/constructor failure because the pipeline ignores cursor and exclusions.

- [ ] **Step 3: Implement online bounded window adaptation**

Use the split coordinator methods introduced in Task 4:

```java
public DecodedRequest decode(RecommendationQuery query)
public RecommendationPage page(
        DecodedRequest request,
        List<RankedMovie> orderedItems,
        boolean sourceTruncated)
```

`DecodedRequest` holds the query and decoded cursor. The one-shot
`page(query, items, sourceTruncated)` method already delegates to these two
methods.

Online flow:

```java
DecodedRequest decoded = pagination.decode(query);
OnlineRecommendationResult online = recommendationService.recommend(
        new OnlineRecommendationRequest(userId, null, maxCandidates));
List<RankedMovie> ranked = toRanked(online.recommendations()).stream()
        .filter(movie -> !query.excludedItemIds().contains(movie.itemId()))
        .toList();
boolean sourceTruncated = online.recommendations().size() == maxCandidates;
RecommendationPage page = pagination.page(decoded, ranked, sourceTruncated);
return new RecommendationResult(query.userId(), page.items(),
        page.nextCursor(), page.hasMore(), trace);
```

In `OnlineRecommendationService`, replace the current
`Math.min(Math.max(k * 4, 12), 100)` calculation. The caller now supplies the
already-bounded candidate-window size, so use:

```java
int recallLimit = Math.min(Math.max(k, 12), 10_000);
```

This keeps the service safe for non-pagination callers while allowing the
configured recommendation pagination ceiling to exceed 100 and provide
lookahead for a public `limit=100`.

Ensure `toRanked` produces the required total order. Positional scores are descending and unique; apply `.sorted(Comparator.comparingDouble(RankedMovie::score).reversed().thenComparing(RankedMovie::itemId))` defensively after filtering.

- [ ] **Step 4: Run online and HTTP tests**

Run:

```bash
mvn -Dtest=OnlineBlendingPipelineTest,OnlineRecommendationServiceTest,OnlinePredictionServerIntegrationTest,ModelV2RecommendIntegrationTest,SequentialStubIntegrationTest test
```

Expected: PASS and JSON includes `"hasMore":false` or true consistently.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/application/online/OnlineBlendingPipeline.java \
  src/main/java/com/recsys/application/online/OnlineRecommendationService.java \
  src/main/java/com/recsys/application/pagination/RecommendationPaginationCoordinator.java \
  src/test/java/com/recsys/application/online/OnlineBlendingPipelineTest.java \
  src/test/java/com/recsys/application/online/OnlineRecommendationServiceTest.java \
  src/test/java/com/recsys/api/online/OnlinePredictionServerIntegrationTest.java \
  src/test/java/com/recsys/api/rest
git commit -m "feat(pagination): unify online recommendation paging"
```

---

### Task 7: Fix Generic MySQL Lookahead

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/persistence/MySqlClient.java`
- Modify: `src/test/java/com/recsys/infrastructure/persistence/MySqlClientTest.java`

**Interfaces:**
- Consumes: a `SqlPlan` whose bound query limit is `pageSize + 1`
- Produces: existing `MySqlClient.PageResult<T>(rows, nextCursor)` with exact terminal semantics

- [ ] **Step 1: Replace exact-size cursor tests with lookahead tests**

```java
@Test
void exactTerminalPageDoesNotEmitCursor() throws Exception {
    SqlPlan plan = planReturning(1, 2); // pageSize == 2, no lookahead
    PageResult<Row> result = client.queryPage(
            connection, plan, 2, row -> cursor(row), mapper);
    assertThat(result.rows()).extracting(Row::id).containsExactly(1L, 2L);
    assertThat(result.nextCursor()).isNull();
}

@Test
void lookaheadIsTrimmedAndEmitsCursorFromLastReturnedRow() throws Exception {
    SqlPlan plan = planReturning(1, 2, 3); // pageSize + 1
    PageResult<Row> result = client.queryPage(
            connection, plan, 2, row -> cursor(row), mapper);
    assertThat(result.rows()).extracting(Row::id).containsExactly(1L, 2L);
    assertThat(MillionScalePaginationSql.SeekCursor.decode(result.nextCursor()).id())
            .isEqualTo(2L);
}
```

Add a test that more than `pageSize + 1` mapped rows throws `IllegalStateException`, proving the plan contract is bounded.

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```bash
mvn -Dtest=MySqlClientTest test
```

Expected: exact-terminal assertion fails because the current implementation emits a cursor for exactly `pageSize` rows.

- [ ] **Step 3: Implement trim-and-prove behavior**

```java
List<T> fetched = query(connection, plan, mapper, queryTimeoutSeconds);
if (fetched.size() > pageSize + 1) {
    throw new IllegalStateException("page query returned more than pageSize + 1 rows");
}
boolean hasMore = fetched.size() > pageSize;
List<T> rows = hasMore
        ? new ArrayList<>(fetched.subList(0, pageSize))
        : fetched;
String nextCursor = null;
if (hasMore) {
    SeekCursor position = cursorExtractor.apply(rows.get(rows.size() - 1));
    if (position != null) nextCursor = position.encode();
}
return new PageResult<>(rows, nextCursor);
```

Update Javadoc to state that the supplied plan must request `pageSize + 1`.

- [ ] **Step 4: Run MySQL unit and catalog regression tests**

Run:

```bash
mvn -Dtest=MySqlClientTest,MovieCatalogRepositoryTest,MovieCatalogServiceTest,MillionScalePaginationSqlTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/infrastructure/persistence/MySqlClient.java \
  src/test/java/com/recsys/infrastructure/persistence/MySqlClientTest.java
git commit -m "fix(pagination): require MySQL page lookahead"
```

---

### Task 8: Runtime Wiring, Secrets, and Operational Documentation

**Files:**
- Modify: `src/main/java/com/recsys/api/serving/RecSysServer.java`
- Modify: `src/main/java/com/recsys/api/online/OnlinePredictionServer.java`
- Create: `src/main/java/com/recsys/application/pagination/RecommendationPaginationRuntime.java`
- Modify: `k8s/base/catalog-serving.yaml`
- Modify: `k8s/base/online-serving.yaml`
- Modify: `k8s/base/configmap.yaml`
- Modify: `scripts/run-microservices-local.sh`
- Modify: `CONFIG_GUIDE.md`
- Modify: `README.md`
- Modify: `docs/system_design/19_Pagination.md`
- Test: `src/test/java/com/recsys/config/EnvConfigTest.java`
- Test: relevant server construction/integration tests

**Interfaces:**
- Consumes: all pagination components from Tasks 1–6
- Produces: identical configured coordinator construction in model/catalog and online runtimes

- [ ] **Step 1: Write failing runtime wiring/configuration tests**

Add a construction holder to avoid duplicated wiring:

```java
public record RecommendationPaginationRuntime(
        RecommendationPaginationCoordinator coordinator,
        int maxCandidates
) {
    public static RecommendationPaginationRuntime fromEnvironment(
            MeterRegistry registry, Clock clock);
}
```

Test with an environment-reader overload:

```java
static RecommendationPaginationRuntime fromEnvironment(
        EnvVars.EnvReader env, MeterRegistry registry, Clock clock)
```

Assert missing signing key fails before server build, previous key is accepted, and the same query/cursor round-trips through coordinators constructed for both serving paths.

- [ ] **Step 2: Run focused wiring tests and verify failure**

Run:

```bash
mvn -Dtest=EnvConfigTest,ModelV2RecommendIntegrationTest,OnlinePredictionServerIntegrationTest test
```

Expected: compilation or construction failures until runtime wiring is added.

- [ ] **Step 3: Add a coordinator factory and wire both servers**

The factory constructs:

```java
RecommendationPaginationConfig config =
        RecommendationPaginationConfig.fromEnvironment(env);
RecommendationPaginationMetrics metrics =
        new RecommendationPaginationMetrics(registry);
RecommendationCursorCodec codec =
        new RecommendationCursorCodec(config, clock);
RecommendationPaginationCoordinator coordinator =
        new RecommendationPaginationCoordinator(
                codec, new CursorPaginationService(), metrics);
return new RecommendationPaginationRuntime(coordinator, config.maxCandidates());
```

In `RecSysServer`, create it from the existing Prometheus registry before
building `RecommendationOrchestrator`. In `OnlinePredictionServer`, reuse the
registry created at startup and inject it into `OnlineBlendingPipeline`.
Use `runtime.coordinator()` and `runtime.maxCandidates()` in both servers; do not
re-read environment variables in the adapters.

- [ ] **Step 4: Add secret and non-secret deployment variables**

Add non-secret defaults to `k8s/base/configmap.yaml`:

```yaml
RECOMMENDATION_CURSOR_MAX_AGE_SECONDS: "900"
RECOMMENDATION_CURSOR_ACCEPT_LEGACY: "true"
RECOMMENDATION_PAGINATION_MAX_CANDIDATES: "500"
```

Add secret references to both serving deployments:

```yaml
- name: RECOMMENDATION_CURSOR_SIGNING_KEY
  valueFrom:
    secretKeyRef:
      name: recsys-secrets
      key: recommendation-cursor-signing-key
- name: RECOMMENDATION_CURSOR_PREVIOUS_KEY
  valueFrom:
    secretKeyRef:
      name: recsys-secrets
      key: recommendation-cursor-previous-key
      optional: true
```

Do not place example key material in the ConfigMap.

- [ ] **Step 5: Update local startup and documentation**

`scripts/run-microservices-local.sh` must require or forward `RECOMMENDATION_CURSOR_SIGNING_KEY` to catalog/model and online processes. Document generation:

```bash
export RECOMMENDATION_CURSOR_SIGNING_KEY="$(openssl rand -hex 32)"
```

In `CONFIG_GUIDE.md`, document exact defaults and ranges from Task 1. In README quick start, add the export before starting the services. In `19_Pagination.md`, move signed recommendation behavior from “approved/planned” to “implemented” only after all implementation tasks pass; retain the legacy retirement sequence.

- [ ] **Step 6: Run config, server, and manifest checks**

Run:

```bash
mvn -Dtest=RecommendationPaginationConfigTest,ModelV2RecommendIntegrationTest,OnlinePredictionServerIntegrationTest test
kubectl kustomize k8s/base >/tmp/recsys-pagination-kustomize.yaml
rg -n "RECOMMENDATION_CURSOR_(SIGNING_KEY|PREVIOUS_KEY|MAX_AGE_SECONDS|ACCEPT_LEGACY)|RECOMMENDATION_PAGINATION_MAX_CANDIDATES" \
  /tmp/recsys-pagination-kustomize.yaml CONFIG_GUIDE.md README.md
```

Expected: Maven PASS; kustomize succeeds; all five settings appear in the appropriate secret/config/documentation surfaces.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/api/serving/RecSysServer.java \
  src/main/java/com/recsys/api/online/OnlinePredictionServer.java \
  src/main/java/com/recsys/application/pagination \
  k8s/base/catalog-serving.yaml k8s/base/online-serving.yaml k8s/base/configmap.yaml \
  scripts/run-microservices-local.sh CONFIG_GUIDE.md README.md \
  docs/system_design/19_Pagination.md src/test/java
git commit -m "feat(pagination): wire signed cursors into serving runtimes"
```

Inspect the staged diff before committing so unrelated tests or user changes are not included.

---

### Task 9: Cross-Path Contract and Full Verification

**Files:**
- Modify: `src/test/java/com/recsys/application/recommendation/CrossPathConsistencyTest.java`
- Modify: `src/test/java/com/recsys/application/recommendation/V2CrossPathLoadTest.java`
- Modify: `src/test/java/com/recsys/api/serving/RecommendationV2DegradedHeaderTest.java`
- Modify: any remaining tests found by compiler errors from the `RecommendationResult` signature change
- Modify: `docs/system_design/19_Pagination.md`

**Interfaces:**
- Consumes: completed model, online, codec, metrics, and MySQL behavior
- Produces: repository-wide evidence that the approved contract is implemented

- [ ] **Step 1: Add a cross-path contract test**

Construct model and online pipelines with the same deterministic ranked input, config, key, and fixed clock. Traverse both with limit 2 and assert:

```java
assertThat(modelFirst.items()).extracting(RankedMovie::itemId)
        .containsExactlyElementsOf(onlineFirst.items().stream()
                .map(RankedMovie::itemId).toList());
assertThat(modelFirst.hasMore()).isTrue();
assertThat(onlineFirst.hasMore()).isTrue();
assertThat(modelSecond.hasMore()).isFalse();
assertThat(onlineSecond.hasMore()).isFalse();
assertThat(modelSecond.nextCursor()).isNull();
assertThat(onlineSecond.nextCursor()).isNull();
```

Tokens need not be byte-identical if issuance timestamps differ, but each must decode to the same tuple under the fixed clock.

- [ ] **Step 2: Add security and budget regression assertions**

In cross-path tests, assert:

- A cursor issued for user A is rejected by both paths for user B.
- Changing exclusions is rejected by both paths.
- Changing only limit succeeds.
- A legacy cursor accepted by both paths yields a signed next cursor.
- A cursor beyond the bounded candidate window terminates with `hasMore=false` and records budget exhaustion.

- [ ] **Step 3: Run focused pagination suite**

Run:

```bash
mvn -Dtest='*Pagination*,*Cursor*,RecommendationOrchestratorTest,OnlineBlendingPipelineTest,CrossPathConsistencyTest,MySqlClientTest,MovieCatalogServiceTest' test
```

Expected: PASS.

- [ ] **Step 4: Run the full unit suite**

Run:

```bash
mvn --batch-mode test
```

Expected: PASS with no compilation failures from the `RecommendationResult` API change.

- [ ] **Step 5: Run optional real-MySQL pagination proof**

When Docker is available:

```bash
mvn -Dgroups=docker -Dtest=MovieCatalogMySqlIntegrationTest test
```

Expected: PASS; tied-score traversal has no omissions/duplicates and `EXPLAIN` uses the expected catalog indexes.

- [ ] **Step 6: Final documentation truth pass**

Search for stale claims and unsigned-current wording:

```bash
rg -n "never skip|never duplicate|unsigned|does not yet paginate|returns a null next cursor|5.?×.?limit" \
  README.md CONFIG_GUIDE.md docs recsys-architecture.html
```

Update only statements made stale by this implementation. Preserve historical specs/plans as historical records unless they claim to describe current behavior.

- [ ] **Step 7: Run final static verification**

Run:

```bash
git diff --check
mvn --batch-mode validate
git status --short
```

Expected: no whitespace errors; Maven validation passes; status contains only intended pagination files.

- [ ] **Step 8: Commit final contract evidence**

```bash
git add src/test/java README.md CONFIG_GUIDE.md docs/system_design/19_Pagination.md \
  recsys-architecture.html
git commit -m "test(pagination): verify cross-serving keyset contract"
```

Stage `recsys-architecture.html` only if the truth-pass changed it.

## Completion Criteria

- Both recommendation serving paths accept and issue the same signed forward live-keyset cursor contract.
- Cursor verification and query binding occur before source work.
- Legacy unsigned cursors are strictly validated, observable, configurable, and upgraded.
- Full tuple seek handles ties, removed anchors, and score changes according to documented live semantics.
- Exact lookahead keeps `hasMore` and `nextCursor` consistent.
- Candidate work is bounded and exhaustion is observable without speculative continuation.
- Generic MySQL paging no longer emits a cursor for an exact terminal page.
- Catalog keyset SQL, cursor security, and index proofs remain green.
- Runtime secrets, rotation, migration, and configuration are documented.
- Focused, full unit, manifest, and optional Docker verification commands pass.
