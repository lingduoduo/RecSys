# Shared Recall Core Implementation Plan (Sub-project 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the multichannel recall quota policy injectable (`QuotaPolicy`) and add a per-port `RecallConfig`, so port 7010 can later share `MultiChannelRecallService` — with zero behavior change to port 6010.

**Architecture:** Introduce a configurable `QuotaPolicy` (ordered fraction maps + a shared slot-rounding helper that reproduces today's `QuotaSpec.warm/cold` exactly). `MultiChannelRecallService` gains a `QuotaPolicy` field; existing constructors default to `QuotaPolicy.defaultMovie()` so all current callers/tests are byte-identical. A `RecallConfig` record + builder bundle per-port wiring, consumed by a `from(RecallConfig)` factory. `RecSysServer` is re-wired through `RecallConfig`.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ.

## Global Constraints

- Java 17, Maven. `mvn test -Dtest=<Class>` runs one test class (Surefire sets `-Xshare:off`).
- **6010 behavior must stay byte-identical** — locked by an equivalence test of `QuotaPolicy.defaultMovie()` against `QuotaSpec.warm/cold`.
- `QuotaSpec` and its `warm/cold` statics stay **untouched** (equivalence oracle; `QuotaSpecTest` must pass unmodified).
- `MultiChannelRecallService`'s existing constructors (1-arg, 5-arg, 6-arg) MUST be preserved and default to `QuotaPolicy.defaultMovie()`.
- `defaultMovie()` numbers: warm = `{embedding 0.60, trending 0.20, genre_history 0.15}` residual `popularity`; cold = `{cold_start 0.50, trending 0.20, popularity 0.20}` residual `genre_history`.
- Slot helper: `remaining=limit; for each non-residual channel in order: slot=clamp(round(fraction*limit),0,remaining); remaining-=slot; residual=max(0,remaining)`. `limit <= 0` throws.
- Out of scope: 7010 adoption, retiring `OnlineRecommendationEngine`, changing 6010 channels/quotas/merge.

---

### Task 1: `QuotaPolicy`

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/coldstart/QuotaPolicy.java`
- Test: `src/test/java/com/recsys/service/retrieval/coldstart/QuotaPolicyTest.java`

**Interfaces:**
- Consumes: existing `QuotaSpec(Map<String,Integer> slots)` record and `QuotaSpec.warm/cold` (as the equivalence oracle in tests only).
- Produces:
  - `public record QuotaPolicy(Map<String,Double> warmFractions, String warmResidualChannel, Map<String,Double> coldFractions, String coldResidualChannel)`
  - `public QuotaSpec warm(int limit)`, `public QuotaSpec cold(int limit)`
  - `public static QuotaPolicy defaultMovie()`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/service/retrieval/coldstart/QuotaPolicyTest.java`:

```java
package com.recsys.service.retrieval.coldstart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaPolicyTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 7, 10, 12, 20, 50, 100})
    void defaultMovieWarm_matchesLegacyQuotaSpec(int limit) {
        assertThat(QuotaPolicy.defaultMovie().warm(limit).slots())
                .isEqualTo(QuotaSpec.warm(limit).slots());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 7, 10, 12, 20, 50, 100})
    void defaultMovieCold_matchesLegacyQuotaSpec(int limit) {
        assertThat(QuotaPolicy.defaultMovie().cold(limit).slots())
                .isEqualTo(QuotaSpec.cold(limit).slots());
    }

    @Test
    void customPolicy_assignsResidualTheRemainder_andNeverExceedsLimit() {
        Map<String, Double> warm = new LinkedHashMap<>();
        warm.put("embedding", 0.50);
        warm.put("trending", 0.30);
        QuotaPolicy policy = new QuotaPolicy(warm, "popularity", warm, "popularity");

        QuotaSpec q = policy.warm(10);
        assertThat(q.slotsFor("embedding")).isEqualTo(5);
        assertThat(q.slotsFor("trending")).isEqualTo(3);
        assertThat(q.slotsFor("popularity")).isEqualTo(2); // residual = 10 - 5 - 3
        int total = q.slotsFor("embedding") + q.slotsFor("trending") + q.slotsFor("popularity");
        assertThat(total).isEqualTo(10);
    }

    @Test
    void limitMustBePositive() {
        assertThatThrownBy(() -> QuotaPolicy.defaultMovie().warm(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuotaPolicy.defaultMovie().cold(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void residualChannelMustNotAppearInFractionMap() {
        Map<String, Double> bad = new LinkedHashMap<>();
        bad.put("popularity", 0.5);
        assertThatThrownBy(() -> new QuotaPolicy(bad, "popularity", bad, "popularity"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeFractionRejected() {
        Map<String, Double> bad = new LinkedHashMap<>();
        bad.put("embedding", -0.1);
        Map<String, Double> ok = new LinkedHashMap<>();
        ok.put("embedding", 0.5);
        assertThatThrownBy(() -> new QuotaPolicy(bad, "popularity", ok, "popularity"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullMapOrResidualRejected() {
        Map<String, Double> ok = new LinkedHashMap<>();
        ok.put("embedding", 0.5);
        assertThatThrownBy(() -> new QuotaPolicy(null, "popularity", ok, "popularity"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QuotaPolicy(ok, null, ok, "popularity"))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=QuotaPolicyTest`
Expected: COMPILATION FAILURE — `QuotaPolicy` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/recsys/service/retrieval/coldstart/QuotaPolicy.java`:

```java
package com.recsys.service.retrieval.coldstart;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-port quota policy: ordered warm/cold channel fractions plus the residual channel that
 * receives whatever slots remain after rounding. Generalises the legacy {@link QuotaSpec#warm}
 * / {@link QuotaSpec#cold} logic so each serving path can supply its own channel set.
 *
 * Slot rounding per request: for each non-residual channel in iteration order,
 * {@code slot = clamp(round(fraction * limit), 0, remaining)}; the residual channel gets
 * {@code max(0, remaining)}. {@link #defaultMovie()} reproduces the legacy numbers exactly.
 */
public record QuotaPolicy(
        Map<String, Double> warmFractions, String warmResidualChannel,
        Map<String, Double> coldFractions, String coldResidualChannel) {

    public QuotaPolicy {
        warmFractions = validateAndCopy(warmFractions, warmResidualChannel, "warm");
        coldFractions = validateAndCopy(coldFractions, coldResidualChannel, "cold");
    }

    private static Map<String, Double> validateAndCopy(Map<String, Double> fractions,
                                                       String residualChannel, String label) {
        Objects.requireNonNull(fractions, label + "Fractions");
        Objects.requireNonNull(residualChannel, label + "ResidualChannel");
        Map<String, Double> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : fractions.entrySet()) {
            Objects.requireNonNull(e.getKey(), label + " channel name");
            Objects.requireNonNull(e.getValue(), label + " fraction");
            if (e.getValue() < 0.0) {
                throw new IllegalArgumentException(label + " fraction must be >= 0: " + e.getKey());
            }
            copy.put(e.getKey(), e.getValue());
        }
        if (copy.containsKey(residualChannel)) {
            throw new IllegalArgumentException(
                    label + " residual channel must not appear in its fraction map: " + residualChannel);
        }
        return Collections.unmodifiableMap(copy);
    }

    public QuotaSpec warm(int limit) { return compute(warmFractions, warmResidualChannel, limit); }

    public QuotaSpec cold(int limit) { return compute(coldFractions, coldResidualChannel, limit); }

    private static QuotaSpec compute(Map<String, Double> fractions, String residualChannel, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive, got: " + limit);
        Map<String, Integer> slots = new LinkedHashMap<>();
        int remaining = limit;
        for (Map.Entry<String, Double> e : fractions.entrySet()) {
            int slot = (int) Math.round(e.getValue() * limit);
            if (slot < 0) slot = 0;
            if (slot > remaining) slot = remaining;
            slots.put(e.getKey(), slot);
            remaining -= slot;
        }
        slots.put(residualChannel, Math.max(0, remaining));
        return new QuotaSpec(slots);
    }

    /** The port-6010 quota numbers, reproducing the legacy {@link QuotaSpec} statics exactly. */
    public static QuotaPolicy defaultMovie() {
        Map<String, Double> warm = new LinkedHashMap<>();
        warm.put("embedding", 0.60);
        warm.put("trending", 0.20);
        warm.put("genre_history", 0.15);
        Map<String, Double> cold = new LinkedHashMap<>();
        cold.put("cold_start", 0.50);
        cold.put("trending", 0.20);
        cold.put("popularity", 0.20);
        return new QuotaPolicy(warm, "popularity", cold, "genre_history");
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=QuotaPolicyTest`
Expected: PASS — both parameterized equivalence tests (9 limits each) plus the validation tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/coldstart/QuotaPolicy.java \
        src/test/java/com/recsys/service/retrieval/coldstart/QuotaPolicyTest.java
git commit -m "feat: add configurable QuotaPolicy reproducing legacy QuotaSpec numbers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Inject `QuotaPolicy` into `MultiChannelRecallService`

**Files:**
- Modify: `src/main/java/com/recsys/service/retrieval/multichannel/MultiChannelRecallService.java`
- Test: `src/test/java/com/recsys/service/retrieval/multichannel/MultiChannelRecallServiceTest.java` (extend)

**Interfaces:**
- Consumes: `QuotaPolicy` (Task 1) — `warm(int)`, `cold(int)`, `defaultMovie()`.
- Produces: new constructor `MultiChannelRecallService(List<RecallChannel>, ChannelHealthMonitor, ExecutorService, long, FaultInjector, EmbeddingStore, QuotaPolicy)`; a `QuotaPolicy quotaPolicy` field; existing constructors delegate with `QuotaPolicy.defaultMovie()`.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/recsys/service/retrieval/multichannel/MultiChannelRecallServiceTest.java` (inside the class; it already imports `RecommendationQuery`, `MovieCandidate`, `EmbeddingStore`, `RecallChannel`, and has a `channel(name, candidates...)` helper). Add the imports `import com.recsys.service.retrieval.coldstart.QuotaPolicy;`, `import java.util.LinkedHashMap;`, and `import com.recsys.online.ops.FaultInjector;` if not already present:

```java
    @Test
    void injectedQuotaPolicy_drivesMerge() {
        // Custom WARM policy: popularity takes everything, embedding is residual (0 slots).
        LinkedHashMap<String, Double> warm = new LinkedHashMap<>();
        warm.put("popularity", 1.0);
        LinkedHashMap<String, Double> cold = new LinkedHashMap<>();
        cold.put("popularity", 1.0);
        QuotaPolicy popularityFirst = new QuotaPolicy(warm, "embedding", cold, "embedding");

        RecallChannel embedding = channel("embedding",
                new MovieCandidate("e1", 0.95, "embedding", java.util.Map.of()),
                new MovieCandidate("e2", 0.85, "embedding", java.util.Map.of()));
        RecallChannel popularity = channel("popularity",
                new MovieCandidate("p1", 0.50, "popularity", java.util.Map.of()),
                new MovieCandidate("p2", 0.40, "popularity", java.util.Map.of()),
                new MovieCandidate("p3", 0.30, "popularity", java.util.Map.of()));

        // Warm user: stub store returns a non-null vector for any id.
        EmbeddingStore warmStore = new AlwaysWarmStore();

        MultiChannelRecallService service = new MultiChannelRecallService(
                java.util.List.of(embedding, popularity),
                new ChannelHealthMonitor(),
                java.util.concurrent.ForkJoinPool.commonPool(),
                200L,
                FaultInjector.NOOP,
                warmStore,
                popularityFirst);

        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 3, java.util.Set.of(), null), 3);

        // popularity got all 3 slots; embedding (0 quota, no gap left) contributes nothing.
        assertThat(recalled).extracting(MovieCandidate::itemId).containsExactly("p1", "p2", "p3");
        assertThat(recalled).extracting(MovieCandidate::channel).containsOnly("popularity");
    }

    // Stub: any id resolves to a non-null vector, so cold-start detection treats the user as warm.
    private static final class AlwaysWarmStore implements EmbeddingStore {
        @Override public float[] getEmbedding(int id) { return new float[]{1f}; }
        @Override public java.util.Map<Integer, float[]> getEmbeddings(java.util.Collection<Integer> ids) {
            return java.util.Map.of();
        }
        @Override public void setEmbedding(int id, float[] v, long ttl) {}
        @Override public void setEmbeddings(java.util.Map<Integer, float[]> v, long ttl) {}
        @Override public java.util.Set<Integer> scanIds(int maxKeys) { return java.util.Set.of(); }
    }
```

(If the test file already imports `FaultInjector`, `ChannelHealthMonitor`, or `List`, do not duplicate those imports.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=MultiChannelRecallServiceTest`
Expected: COMPILATION FAILURE — the 7-arg constructor does not exist.

- [ ] **Step 3: Modify `MultiChannelRecallService`**

(a) Add the import near the other `com.recsys.service.retrieval` imports:

```java
import com.recsys.service.retrieval.coldstart.QuotaPolicy;
```

(b) Add the field next to `userEmbeddingStore` (line ~34):

```java
    private final QuotaPolicy quotaPolicy;
```

(c) Change the existing 6-arg constructor (lines ~51-66) to delegate to a new 7-arg constructor with the default policy, and add the 7-arg constructor that does the field assignment. Replace the existing 6-arg constructor body:

```java
    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector,
                                     EmbeddingStore userEmbeddingStore) {
        this(channels, healthMonitor, executor, channelTimeoutMs, faultInjector,
                userEmbeddingStore, QuotaPolicy.defaultMovie());
    }

    public MultiChannelRecallService(List<RecallChannel> channels,
                                     ChannelHealthMonitor healthMonitor,
                                     ExecutorService executor,
                                     long channelTimeoutMs,
                                     FaultInjector faultInjector,
                                     EmbeddingStore userEmbeddingStore,
                                     QuotaPolicy quotaPolicy) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("at least one recall channel is required");
        }
        this.channels            = List.copyOf(channels);
        this.healthMonitor       = Objects.requireNonNull(healthMonitor, "healthMonitor");
        this.executor            = Objects.requireNonNull(executor, "executor");
        this.channelTimeoutMs    = Math.max(1L, channelTimeoutMs);
        this.faultInjector       = faultInjector == null ? FaultInjector.NOOP : faultInjector;
        this.userEmbeddingStore  = userEmbeddingStore;
        this.quotaPolicy         = quotaPolicy == null ? QuotaPolicy.defaultMovie() : quotaPolicy;
    }
```

The 1-arg and 5-arg constructors are unchanged — they delegate to the 6-arg one, which now supplies `QuotaPolicy.defaultMovie()`.

(d) In `recall()` (lines ~77, ~79), replace the two `QuotaSpec` static calls with the injected policy:

```java
                boolean isCold = userEmbeddingStore.getEmbedding(userId) == null;
                quota = isCold ? quotaPolicy.cold(limit) : quotaPolicy.warm(limit);
            } catch (NumberFormatException e) {
                quota = quotaPolicy.cold(limit);
            }
```

(The `import ...coldstart.QuotaSpec;` stays — `quotaPolicy.cold/warm` return `QuotaSpec`, still used as the local `quota` type.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=MultiChannelRecallServiceTest`
Expected: PASS — the new `injectedQuotaPolicy_drivesMerge` test plus all existing tests green (existing constructors now default to `defaultMovie()`, identical behavior).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/multichannel/MultiChannelRecallService.java \
        src/test/java/com/recsys/service/retrieval/multichannel/MultiChannelRecallServiceTest.java
git commit -m "feat: inject QuotaPolicy into MultiChannelRecallService (default = 6010 numbers)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `RecallConfig` + `from(RecallConfig)` factory

**Files:**
- Create: `src/main/java/com/recsys/service/retrieval/multichannel/RecallConfig.java`
- Modify: `src/main/java/com/recsys/service/retrieval/multichannel/MultiChannelRecallService.java`
- Test: `src/test/java/com/recsys/service/retrieval/multichannel/RecallConfigTest.java`

**Interfaces:**
- Consumes: `MultiChannelRecallService` 7-arg constructor (Task 2); `QuotaPolicy.defaultMovie()`; `RecallChannel`, `ChannelHealthMonitor`, `FaultInjector`, `EmbeddingStore`.
- Produces: `RecallConfig` record + `RecallConfig.Builder`; `static MultiChannelRecallService MultiChannelRecallService.from(RecallConfig config)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/service/retrieval/multichannel/RecallConfigTest.java`:

```java
package com.recsys.service.retrieval.multichannel;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.online.ops.FaultInjector;
import com.recsys.service.retrieval.RecallChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecallConfigTest {

    private static RecallChannel channel(String name, MovieCandidate... candidates) {
        return new RecallChannel() {
            @Override public String name() { return name; }
            @Override public List<MovieCandidate> recall(RecommendationQuery query, int limit) {
                return List.of(candidates);
            }
        };
    }

    @Test
    void builderRejectsEmptyChannels() {
        assertThatThrownBy(() -> RecallConfig.builder()
                .channels(List.of())
                .executor(ForkJoinPool.commonPool())
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderRejectsNullExecutor() {
        assertThatThrownBy(() -> RecallConfig.builder()
                .channels(List.of(channel("c", new MovieCandidate("1", 1.0, "c", Map.of()))))
                .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromConfig_buildsWorkingService_withDefaults() {
        RecallChannel c = channel("c",
                new MovieCandidate("1", 0.9, "c", Map.of()),
                new MovieCandidate("2", 0.8, "c", Map.of()));

        MultiChannelRecallService service = MultiChannelRecallService.from(
                RecallConfig.builder()
                        .channels(List.of(c))
                        .executor(ForkJoinPool.commonPool())
                        .build());

        // No userEmbeddingStore set -> legacy (non-quota) merge path; both candidates returned.
        List<MovieCandidate> recalled = service.recall(
                new RecommendationQuery("1", 5, Set.of(), null), 5);
        assertThat(recalled).extracting(MovieCandidate::itemId).containsExactlyInAnyOrder("1", "2");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=RecallConfigTest`
Expected: COMPILATION FAILURE — `RecallConfig` and `MultiChannelRecallService.from` do not exist.

- [ ] **Step 3: Create `RecallConfig`**

Create `src/main/java/com/recsys/service/retrieval/multichannel/RecallConfig.java`:

```java
package com.recsys.service.retrieval.multichannel;

import com.recsys.infrastructure.vectordb.EmbeddingStore;
import com.recsys.online.ops.FaultInjector;
import com.recsys.service.retrieval.RecallChannel;
import com.recsys.service.retrieval.coldstart.QuotaPolicy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Per-port wiring for {@link MultiChannelRecallService}. Built via {@link #builder()} and
 * consumed by {@link MultiChannelRecallService#from(RecallConfig)}. {@code userEmbeddingStore}
 * may be null (disables cold-start detection — legacy merge).
 */
public record RecallConfig(
        List<RecallChannel> channels,
        QuotaPolicy quotaPolicy,
        long channelTimeoutMs,
        ExecutorService executor,
        ChannelHealthMonitor healthMonitor,
        FaultInjector faultInjector,
        EmbeddingStore userEmbeddingStore) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private List<RecallChannel> channels;
        private QuotaPolicy quotaPolicy = QuotaPolicy.defaultMovie();
        private long channelTimeoutMs = 200L;
        private ExecutorService executor;
        private ChannelHealthMonitor healthMonitor = new ChannelHealthMonitor();
        private FaultInjector faultInjector = FaultInjector.NOOP;
        private EmbeddingStore userEmbeddingStore;

        public Builder channels(List<RecallChannel> channels) { this.channels = channels; return this; }
        public Builder quotaPolicy(QuotaPolicy quotaPolicy) { this.quotaPolicy = quotaPolicy; return this; }
        public Builder channelTimeoutMs(long ms) { this.channelTimeoutMs = ms; return this; }
        public Builder executor(ExecutorService executor) { this.executor = executor; return this; }
        public Builder healthMonitor(ChannelHealthMonitor m) { this.healthMonitor = m; return this; }
        public Builder faultInjector(FaultInjector fi) { this.faultInjector = fi; return this; }
        public Builder userEmbeddingStore(EmbeddingStore store) { this.userEmbeddingStore = store; return this; }

        public RecallConfig build() {
            if (channels == null || channels.isEmpty()) {
                throw new IllegalArgumentException("at least one recall channel is required");
            }
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(healthMonitor, "healthMonitor");
            if (channelTimeoutMs < 1L) {
                throw new IllegalArgumentException("channelTimeoutMs must be >= 1, got: " + channelTimeoutMs);
            }
            return new RecallConfig(channels,
                    quotaPolicy == null ? QuotaPolicy.defaultMovie() : quotaPolicy,
                    channelTimeoutMs, executor, healthMonitor,
                    faultInjector == null ? FaultInjector.NOOP : faultInjector,
                    userEmbeddingStore);
        }
    }
}
```

- [ ] **Step 4: Add the `from` factory to `MultiChannelRecallService`**

Add this static method to `MultiChannelRecallService` (e.g. just after the constructors):

```java
    /** Builds a service from a per-port {@link RecallConfig}. */
    public static MultiChannelRecallService from(RecallConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        return new MultiChannelRecallService(
                config.channels(),
                config.healthMonitor(),
                config.executor(),
                config.channelTimeoutMs(),
                config.faultInjector(),
                config.userEmbeddingStore(),
                config.quotaPolicy());
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn test -Dtest=RecallConfigTest,MultiChannelRecallServiceTest`
Expected: PASS — `RecallConfigTest` (3) and all `MultiChannelRecallServiceTest` cases green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/service/retrieval/multichannel/RecallConfig.java \
        src/main/java/com/recsys/service/retrieval/multichannel/MultiChannelRecallService.java \
        src/test/java/com/recsys/service/retrieval/multichannel/RecallConfigTest.java
git commit -m "feat: add RecallConfig + MultiChannelRecallService.from(config)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Re-wire `RecSysServer` through `RecallConfig`

**Files:**
- Modify: `src/main/java/com/recsys/serving/RecSysServer.java:92-105`

**Interfaces:**
- Consumes: `RecallConfig.builder()` (Task 3); `QuotaPolicy.defaultMovie()`; `MultiChannelRecallService.from(...)`.
- Produces: identical recall service wiring, expressed via config.

- [ ] **Step 1: Apply the re-wire**

Add imports near the other `com.recsys.service.retrieval` imports in `RecSysServer.java`:

```java
import com.recsys.service.retrieval.multichannel.RecallConfig;
import com.recsys.service.retrieval.coldstart.QuotaPolicy;
```

Replace the `MultiChannelRecallService recallService = new MultiChannelRecallService(...)` block (lines ~92-105) with:

```java
            MultiChannelRecallService recallService = MultiChannelRecallService.from(
                    RecallConfig.builder()
                            .channels(List.of(
                                    new EmbeddingChannel(candidateGenerator),
                                    new TrendingChannel(topkStore, List.of("last_hour", "last_day")),
                                    new GenreHistoryChannel(candidateGenerator),
                                    new PopularityChannel(dataManager, globalPopStore),
                                    new ColdStartChannel(topkStore, globalPopStore)))
                            .quotaPolicy(QuotaPolicy.defaultMovie())
                            .healthMonitor(new ChannelHealthMonitor())
                            .executor(executor)
                            .channelTimeoutMs(DEFAULT_CHANNEL_TIMEOUT_MS)
                            .faultInjector(FaultInjector.NOOP)
                            .userEmbeddingStore(userEmbCache)
                            .build());
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run 6010 regression tests**

Run: `mvn test -Dtest=RecSysServerIntegrationTest,RecSysServerRegressionTest`
Expected: PASS — recall output identical (same channels, `defaultMovie()` quotas).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/recsys/serving/RecSysServer.java
git commit -m "refactor: wire RecSysServer recall via RecallConfig (behavior identical)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Full-suite regression guard

**Files:** none (verification only).

- [ ] **Step 1: Full build + test suite**

Run: `mvn test`
Expected: BUILD SUCCESS — all tests green, including `QuotaSpecTest` (unchanged), `QuotaPolicyTest`, `RecallConfigTest`, `MultiChannelRecallServiceTest`, `ColdStartChannelTest`, `RecSysServerIntegrationTest`, `RecSysServerRegressionTest`.

- [ ] **Step 2: Embedding recall load test (opt-in)**

Run: `mvn test -DexcludedGroups="" -Dgroups=load -Dtest=EmbeddingRecallLoadTest`
Expected: PASS — no throughput regression (the quota source changed; the merge path did not).

- [ ] **Step 3: No commit** (verification only).

---

## Self-Review

**Spec coverage:**
- Spec §3.1 `QuotaPolicy` (fraction maps, residual, helper, `defaultMovie`, validation) → Task 1.
- Spec §3.2 `RecallConfig` + builder + defaults → Task 3.
- Spec §3.3 `MultiChannelRecallService` (QuotaPolicy field, recall() uses it, `from` factory, constructors preserved) → Tasks 2 (field/constructors/recall) + 3 (`from`).
- Spec §3.4 `RecSysServer` re-wire → Task 4.
- Spec §3.5 `QuotaSpec` untouched → no task touches it (verified: only `recall()`'s call sites change, statics remain).
- Spec §6 testing (equivalence oracle, custom policy, builder validation, regression) → Tasks 1, 2, 3, 5.

**Placeholder scan:** none — all steps carry full code and exact commands.

**Type consistency:**
- `QuotaPolicy.warm(int)/cold(int) -> QuotaSpec`, `defaultMovie()` — defined Task 1, used Tasks 2/3/4.
- 7-arg `MultiChannelRecallService(..., QuotaPolicy)` — defined Task 2, used by `from` (Task 3).
- `RecallConfig.builder()...build()` and `MultiChannelRecallService.from(RecallConfig)` — defined Task 3, used Task 4.
- `RecommendationQuery("1", 3, Set.of(), null)` and `MovieCandidate(String,double,String,Map)` match the existing test usages verified in the source.
- `EmbeddingStore` 5-method surface (getEmbedding/getEmbeddings/setEmbedding/setEmbeddings/scanIds) matches the interface used by the `AlwaysWarmStore` stub.
