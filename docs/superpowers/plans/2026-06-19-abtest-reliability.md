# A/B Test Reliability — Implementation Plan (Sub-project 2 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make port-8080 A/B testing reliable: stable hash-to-keyspace bucketing (no reshuffle on allocation change), async Kafka exposure logging, and serve-control fallback when an assigned variant's artifacts fail to load.

**Architecture:** `StableBucketer` hashes `userId:layer` into a fixed `[0,10000)` keyspace; `ABTestService` maps the slot to a variant via ordered ranges from `bucketAPercent`/`bucketBPercent`. `VariantRuntimeResolver` serves the control runtime (with a cooldown guard + metric) when an assigned variant won't load. `AbExposureLogger` emits a structured `ExposureEvent` per served request via a reused `AsyncEventPublisher`. The controller orchestrates; `RecommendationService` keys cache + response on the *served* variant.

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Mockito, Micrometer, Jackson.

## Global Constraints

- Java 17, Maven. `mvn test -Dtest=<Class>` runs one class. Branch stacks on `feat/model-serving-recall-adoption` (SP1, PR #129).
- **HTTP contract unchanged:** `RecommendResponse{userId, modelVersion, abTestVariant, recommendations}`. `abTestVariant` now always reports the **served** variant.
- **A/B disabled default = byte-identical to today:** every user → default variant via `Assignment.control`; NO exposure events when disabled.
- **Stable bucketing:** keyspace `KEYSPACE = 10_000`; ranges `A = [0, aPct*100)`, `B = [aPct*100, (aPct+bPct)*100)`, control = remainder. Percents are ints, `aPct >= 0`, `bPct >= 0`, `aPct + bPct <= 100`. Defaults `20`/`20` (preserves today's 20/20/60).
- **`Assignment.bucket` is replaced by `Assignment.slot`** (the `[0,10000)` keyspace position; `-1` for control/disabled).
- **Fallback honesty:** response + exposure report the served variant; exposure carries `fellBackFrom = <assigned>` only on fallback; metric `recsys.abtest.variant_fallback` (tag `variant`).
- **Exposure non-blocking:** publish via `AsyncEventPublisher` (bounded, drops under backpressure); never block/fail the request.
- Do NOT modify `MultiChannelRecallService`/`RecallConfig`/`QuotaPolicy`, the online `AsyncEventPublisher`/`LogCollector` classes, or SP1's recall pipeline.

---

### Task 1: `StableBucketer` — well-distributed hash → keyspace slot

**Files:**
- Create: `src/main/java/com/recsys/model/service/StableBucketer.java`
- Test: `src/test/java/com/recsys/model/service/StableBucketerTest.java`

**Interfaces:**
- Produces: `public static int StableBucketer.slot(String userId, String layerName)` → deterministic value in `[0, StableBucketer.KEYSPACE)`; `public static final int KEYSPACE = 10_000`.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.model.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StableBucketerTest {

    @Test
    void slotIsDeterministic() {
        assertThat(StableBucketer.slot("123", "default"))
                .isEqualTo(StableBucketer.slot("123", "default"));
    }

    @Test
    void slotIsWithinKeyspace() {
        for (int i = 0; i < 5_000; i++) {
            int slot = StableBucketer.slot(Integer.toString(i), "default");
            assertThat(slot).isGreaterThanOrEqualTo(0).isLessThan(StableBucketer.KEYSPACE);
        }
    }

    @Test
    void sequentialIdsSpreadAcrossKeyspace() {
        // Sequential numeric ids must NOT cluster (the String.hashCode weakness this replaces).
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            slots.add(StableBucketer.slot(Integer.toString(i), "default"));
        }
        // With a good hash over 10k ids into a 10k keyspace, expect a high number of distinct slots.
        assertThat(slots.size()).isGreaterThan(6_000);
    }

    @Test
    void differentLayersGiveIndependentSlots() {
        // For most users the two layers differ; assert at least the keys are not identical wholesale.
        int differ = 0;
        for (int i = 0; i < 1_000; i++) {
            if (StableBucketer.slot(Integer.toString(i), "a") != StableBucketer.slot(Integer.toString(i), "b")) {
                differ++;
            }
        }
        assertThat(differ).isGreaterThan(900);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=StableBucketerTest`
Expected: COMPILATION FAILURE — `StableBucketer` does not exist.

- [ ] **Step 3: Create the class**

```java
package com.recsys.model.service;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic, well-distributed hashing of {@code userId:layer} into a fixed keyspace.
 * Replaces {@code String.hashCode() % trafficSplitNumber}: a stable hash plus a fixed keyspace
 * lets traffic allocations move as ranges without reshuffling users (see ABTestService).
 * Stable across JVMs (no reliance on JVM-specific hashing).
 */
public final class StableBucketer {

    public static final int KEYSPACE = 10_000;

    private StableBucketer() {}

    /** Returns the keyspace slot in {@code [0, KEYSPACE)} for the given user and layer. */
    public static int slot(String userId, String layerName) {
        String key = (userId == null ? "" : userId) + ":" + (layerName == null ? "" : layerName);
        long h = hash64(key.getBytes(StandardCharsets.UTF_8));
        return (int) Long.remainderUnsigned(h, KEYSPACE);
    }

    // FNV-1a accumulation followed by the murmur3 fmix64 finalizer — good avalanche, no dependency.
    private static long hash64(byte[] data) {
        long h = 0xcbf29ce484222325L;          // FNV-1a 64-bit offset basis
        for (byte b : data) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;               // FNV-1a 64-bit prime
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=StableBucketerTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/model/service/StableBucketer.java \
        src/test/java/com/recsys/model/service/StableBucketerTest.java
git commit -m "feat: add StableBucketer (well-distributed hash to fixed keyspace)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Stable bucketing in `ABTestConfig` + `ABTestService`

**Files:**
- Modify: `src/main/java/com/recsys/config/ABTestConfig.java`
- Modify: `src/main/java/com/recsys/model/service/ABTestService.java`
- Modify: `src/main/resources/application.yml` (the `recsys.ab-test` block)
- Test: `src/test/java/com/recsys/model/service/ABTestServiceTest.java` (rework)

**Interfaces:**
- Consumes: `StableBucketer.slot`, `StableBucketer.KEYSPACE` (Task 1).
- Produces: `ABTestConfig.getBucketAPercent()/getBucketBPercent()` (replacing `getTrafficSplitNumber`); `ABTestService.Assignment(String variant, int slot, String layerName, boolean inExperiment)` with `Assignment.control(variant, layer)`; `ABTestService.defaultVariant()` accessor.

- [ ] **Step 1: Edit `ABTestConfig`** — replace the `trafficSplitNumber` field/accessors and drop `volatile`:

Remove:
```java
    @Min(2)
    private int trafficSplitNumber = 5;
```
and its getter/setter. Add (next to the other fields):
```java
    @Min(0)
    private int bucketAPercent = 20;

    @Min(0)
    private int bucketBPercent = 20;
```
Change `private volatile String defaultVariant = "training";` to `private String defaultVariant = "training";` (drop `volatile`). Add accessors + a cross-field validation method:
```java
    public int getBucketAPercent() { return bucketAPercent; }
    public void setBucketAPercent(int bucketAPercent) { this.bucketAPercent = bucketAPercent; }

    public int getBucketBPercent() { return bucketBPercent; }
    public void setBucketBPercent(int bucketBPercent) { this.bucketBPercent = bucketBPercent; }

    @jakarta.validation.constraints.AssertTrue(message = "bucketAPercent + bucketBPercent must be <= 100")
    public boolean isAllocationWithinBounds() {
        return bucketAPercent + bucketBPercent <= 100;
    }
```
Remove the now-unused `import jakarta.validation.constraints.Min;`? Keep it — `@Min(0)` still uses it.

- [ ] **Step 2: Rework `ABTestServiceTest`** (write the new expectations first)

Replace the `setUp` config lines and the bucket-based tests. Key changes: `config.setTrafficSplitNumber(5)` → `config.setBucketAPercent(20); config.setBucketBPercent(20);`. Replace `assignment.bucket()` assertions with `assignment.slot()` and the local hash helper. Replace the full file with:

```java
package com.recsys.model.service;

import com.recsys.config.ABTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ABTestServiceTest {

    private static final String LAYER = "default";

    private ABTestConfig config;
    private ABTestService service;

    @BeforeEach
    void setUp() {
        config = new ABTestConfig();
        config.setEnabled(true);
        config.setLayerName(LAYER);
        config.setBucketAPercent(20);
        config.setBucketBPercent(20);
        config.setBucketAVariant("test");
        config.setBucketBVariant("training");
        config.setDefaultVariant("training");
        service = new ABTestService(config);
    }

    @Test
    void disabled_alwaysReturnsDefault() {
        config.setEnabled(false);
        assertThat(service.getVariantForUser("123")).isEqualTo("training");
        assertThat(service.getVariantForUser("456")).isEqualTo("training");
    }

    @Test
    void nullOrBlankUserId_returnsControl() {
        assertThat(service.getVariantForUser(null)).isEqualTo("training");
        assertThat(service.getVariantForUser("  ")).isEqualTo("training");
        ABTestService.Assignment a = service.getAssignmentForUser("  ");
        assertThat(a.slot()).isEqualTo(-1);
        assertThat(a.inExperiment()).isFalse();
    }

    @Test
    void assignmentVariantMatchesSlotRanges() {
        // A = slot [0,2000), B = [2000,4000), control = [4000,10000) for 20/20.
        for (int i = 0; i < 2_000; i++) {
            String userId = Integer.toString(i);
            ABTestService.Assignment a = service.getAssignmentForUser(userId);
            int slot = StableBucketer.slot(userId, LAYER);
            String expected = slot < 2_000 ? "test" : slot < 4_000 ? "training-B" : "training";
            // bucketBVariant is "training" and default is also "training"; assert by range directly:
            if (slot < 2_000) {
                assertThat(a.variant()).isEqualTo("test");
                assertThat(a.inExperiment()).isTrue();
            } else if (slot < 4_000) {
                assertThat(a.variant()).isEqualTo("training");   // bucket B variant
                assertThat(a.inExperiment()).isTrue();
            } else {
                assertThat(a.variant()).isEqualTo("training");   // control
                assertThat(a.inExperiment()).isFalse();
            }
            assertThat(a.slot()).isEqualTo(slot);
            assertThat(a.layerName()).isEqualTo(LAYER);
        }
    }

    @Test
    void roughlyTwentyPercentInA() {
        int inA = 0, n = 10_000;
        for (int i = 0; i < n; i++) {
            if ("test".equals(service.getVariantForUser(Integer.toString(i)))) inA++;
        }
        // 20% target; allow generous tolerance for hash noise.
        assertThat(inA).isBetween(1_500, 2_500);
    }

    @Test
    void changingBPercentDoesNotReshuffleA() {
        // Record A-members at 20/20, then widen B to 30 and confirm every A-member stays in A.
        java.util.List<String> aMembers = new java.util.ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            String u = Integer.toString(i);
            if ("test".equals(service.getVariantForUser(u))) aMembers.add(u);
        }
        config.setBucketBPercent(30);
        for (String u : aMembers) {
            assertThat(service.getVariantForUser(u)).as("A-member %s after B 20->30", u).isEqualTo("test");
        }
    }

    @Test
    void defaultVariantAccessor() {
        assertThat(service.defaultVariant()).isEqualTo("training");
    }
}
```

Run: `mvn test -Dtest=ABTestServiceTest`
Expected: COMPILATION FAILURE — `setBucketAPercent`, `assignment.slot()`, `service.defaultVariant()` don't exist yet.

- [ ] **Step 3: Rework `ABTestService`** — slot-based bucketing + immutable snapshot. Replace the body from the `Assignment getAssignmentForUser(String, String)` method through `resolveBucket` with:

```java
    public Assignment getAssignmentForUser(String userId, String layerName) {
        Snapshot s = snapshot();
        String layer = (layerName == null || layerName.isBlank()) ? s.layerName() : layerName;
        if (!s.enabled() || userId == null || userId.isBlank()) {
            return Assignment.control(s.defaultVariant(), layer);
        }
        int slot = StableBucketer.slot(userId, layer);
        int aEnd = s.bucketAPercent() * (StableBucketer.KEYSPACE / 100);
        int bEnd = aEnd + s.bucketBPercent() * (StableBucketer.KEYSPACE / 100);
        if (slot < aEnd) {
            log.debug("user {} layer '{}' slot {} -> A ({})", userId, layer, slot, s.bucketAVariant());
            return new Assignment(s.bucketAVariant(), slot, layer, true);
        }
        if (slot < bEnd) {
            log.debug("user {} layer '{}' slot {} -> B ({})", userId, layer, slot, s.bucketBVariant());
            return new Assignment(s.bucketBVariant(), slot, layer, true);
        }
        return new Assignment(s.defaultVariant(), slot, layer, false);
    }

    /** Default/control variant from config — used by VariantRuntimeResolver for fallback. */
    public String defaultVariant() {
        return config.getDefaultVariant();
    }

    private Snapshot snapshot() {
        return new Snapshot(config.isEnabled(), config.getBucketAPercent(), config.getBucketBPercent(),
                config.getBucketAVariant(), config.getBucketBVariant(), config.getDefaultVariant(),
                config.getLayerName());
    }

    private record Snapshot(boolean enabled, int bucketAPercent, int bucketBPercent,
                            String bucketAVariant, String bucketBVariant, String defaultVariant,
                            String layerName) {}
```

Delete the old `resolveBucket` method and the `VARIANT_A_BUCKET`/`VARIANT_B_BUCKET` constants. Replace the `Assignment` record with:

```java
    public record Assignment(String variant, int slot, String layerName, boolean inExperiment) {
        public static Assignment control(String variant, String layerName) {
            return new Assignment(variant, -1, layerName, false);
        }
    }
```

Keep `getVariantForUser(...)`, `getAssignmentForUser(String)`, and `normalizeLayerName` is no longer needed (inlined) — remove it if now unused.

- [ ] **Step 4: Update `application.yml`** — in the `recsys.ab-test` block, replace the `traffic-split-number` line with:

```yaml
    bucket-a-percent: ${RECSYS_AB_BUCKET_A_PERCENT:20}
    bucket-b-percent: ${RECSYS_AB_BUCKET_B_PERCENT:20}
```

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=ABTestServiceTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/config/ABTestConfig.java \
        src/main/java/com/recsys/model/service/ABTestService.java \
        src/main/resources/application.yml \
        src/test/java/com/recsys/model/service/ABTestServiceTest.java
git commit -m "feat: stable slot-based A/B bucketing (percent ranges, no reshuffle)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `VariantRuntimeResolver` — serve-control fallback + cooldown

**Files:**
- Create: `src/main/java/com/recsys/model/service/VariantRuntimeResolver.java`
- Test: `src/test/java/com/recsys/model/service/VariantRuntimeResolverTest.java`

**Interfaces:**
- Consumes: `ModelRuntimeProvider.getRuntime(String)` (throws on load failure); `io.micrometer.core.instrument.MeterRegistry`.
- Produces: `VariantRuntimeResolver(ModelRuntimeProvider, MeterRegistry)`; `Resolved resolve(String assignedVariant, String defaultVariant)`; `record Resolved(ModelRuntime runtime, String servedVariant, boolean fellBack)`.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.model.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VariantRuntimeResolverTest {

    private final ModelRuntime testRuntime = mock(ModelRuntime.class);
    private final ModelRuntime controlRuntime = mock(ModelRuntime.class);

    private VariantRuntimeResolver resolver(ModelRuntimeProvider provider, AtomicLong clock) {
        return new VariantRuntimeResolver(provider, new SimpleMeterRegistry(), 60_000L, clock::get);
    }

    @Test
    void healthyVariant_servesItself() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("test")).thenReturn(testRuntime);
        VariantRuntimeResolver r = resolver(provider, new AtomicLong(0));

        VariantRuntimeResolver.Resolved resolved = r.resolve("test", "training");

        assertThat(resolved.runtime()).isSameAs(testRuntime);
        assertThat(resolved.servedVariant()).isEqualTo("test");
        assertThat(resolved.fellBack()).isFalse();
    }

    @Test
    void assignedEqualsDefault_noFallback() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        VariantRuntimeResolver r = resolver(provider, new AtomicLong(0));

        VariantRuntimeResolver.Resolved resolved = r.resolve("training", "training");

        assertThat(resolved.servedVariant()).isEqualTo("training");
        assertThat(resolved.fellBack()).isFalse();
    }

    @Test
    void brokenVariant_servesControl_andCooldownSkipsRebuild() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("test")).thenThrow(new IllegalStateException("artifacts missing"));
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        AtomicLong clock = new AtomicLong(0);
        VariantRuntimeResolver r = resolver(provider, clock);

        VariantRuntimeResolver.Resolved first = r.resolve("test", "training");
        assertThat(first.runtime()).isSameAs(controlRuntime);
        assertThat(first.servedVariant()).isEqualTo("training");
        assertThat(first.fellBack()).isTrue();

        // Within cooldown: must NOT attempt to rebuild "test" again.
        clock.set(30_000L);
        VariantRuntimeResolver.Resolved second = r.resolve("test", "training");
        assertThat(second.fellBack()).isTrue();
        verify(provider, times(1)).getRuntime("test");   // only the first attempt
    }

    @Test
    void afterCooldown_retriesAssignedVariant() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("test"))
                .thenThrow(new IllegalStateException("missing"))
                .thenReturn(testRuntime);                 // fixed on the retry
        when(provider.getRuntime("training")).thenReturn(controlRuntime);
        AtomicLong clock = new AtomicLong(0);
        VariantRuntimeResolver r = resolver(provider, clock);

        assertThat(r.resolve("test", "training").fellBack()).isTrue();
        clock.set(60_001L);                                // cooldown expired
        VariantRuntimeResolver.Resolved retry = r.resolve("test", "training");
        assertThat(retry.servedVariant()).isEqualTo("test");
        assertThat(retry.fellBack()).isFalse();
        verify(provider, times(2)).getRuntime("test");
    }

    @Test
    void brokenControl_propagates() {
        ModelRuntimeProvider provider = mock(ModelRuntimeProvider.class);
        when(provider.getRuntime("test")).thenThrow(new IllegalStateException("missing"));
        when(provider.getRuntime("training")).thenThrow(new IllegalStateException("control missing too"));
        VariantRuntimeResolver r = resolver(provider, new AtomicLong(0));

        assertThatThrownBy(() -> r.resolve("test", "training"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=VariantRuntimeResolverTest`
Expected: COMPILATION FAILURE — `VariantRuntimeResolver` does not exist.

- [ ] **Step 3: Create the class**

```java
package com.recsys.model.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Resolves the {@link ModelRuntime} for an A/B-assigned variant, falling back to the default/control
 * variant when the assigned variant's artifacts fail to load. A failed variant is held in a cooldown
 * so the failing ONNX build is not re-paid on every request ({@code computeIfAbsent} does not cache the
 * exception). After the cooldown one retry is allowed so a redeployed artifact recovers without a restart.
 */
@Service
public class VariantRuntimeResolver {

    static final long DEFAULT_COOLDOWN_MS = 60_000L;
    private static final Logger log = LoggerFactory.getLogger(VariantRuntimeResolver.class);

    private final ModelRuntimeProvider provider;
    private final MeterRegistry registry;
    private final long cooldownMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> failedUntilMs = new ConcurrentHashMap<>();

    @Autowired
    public VariantRuntimeResolver(ModelRuntimeProvider provider, MeterRegistry registry) {
        this(provider, registry, DEFAULT_COOLDOWN_MS, System::currentTimeMillis);
    }

    VariantRuntimeResolver(ModelRuntimeProvider provider, MeterRegistry registry, long cooldownMs, LongSupplier clock) {
        this.provider = provider;
        this.registry = registry;
        this.cooldownMs = cooldownMs;
        this.clock = clock;
    }

    public Resolved resolve(String assignedVariant, String defaultVariant) {
        if (assignedVariant.equals(defaultVariant)) {
            return new Resolved(provider.getRuntime(defaultVariant), defaultVariant, false);
        }
        long now = clock.getAsLong();
        Long until = failedUntilMs.get(assignedVariant);
        if (until == null || now >= until) {
            failedUntilMs.remove(assignedVariant);
            try {
                return new Resolved(provider.getRuntime(assignedVariant), assignedVariant, false);
            } catch (RuntimeException e) {
                failedUntilMs.put(assignedVariant, now + cooldownMs);
                recordFallback(assignedVariant);
                log.warn("variant '{}' failed to load; serving control '{}'", assignedVariant, defaultVariant, e);
            }
        } else {
            recordFallback(assignedVariant);
        }
        // Control failing here propagates — a broken control is a genuine outage, not masked.
        return new Resolved(provider.getRuntime(defaultVariant), defaultVariant, true);
    }

    private void recordFallback(String variant) {
        registry.counter("recsys.abtest.variant_fallback", "variant", variant).increment();
    }

    public record Resolved(ModelRuntime runtime, String servedVariant, boolean fellBack) {}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=VariantRuntimeResolverTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/model/service/VariantRuntimeResolver.java \
        src/test/java/com/recsys/model/service/VariantRuntimeResolverTest.java
git commit -m "feat: add VariantRuntimeResolver (serve-control fallback + cooldown)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `AbExposureLogger` + `AsyncEventPublisher` bean

**Files:**
- Create: `src/main/java/com/recsys/model/service/AbExposureLogger.java`
- Create: `src/main/java/com/recsys/model/config/ModelEventConfig.java`
- Test: `src/test/java/com/recsys/model/service/AbExposureLoggerTest.java`

**Interfaces:**
- Consumes: `com.recsys.online.event.AsyncEventPublisher.publish(String)`; `ABTestConfig.isEnabled()`; `ABTestService.Assignment` (`variant()`, `layerName()`, `slot()`, `inExperiment()`).
- Produces: `AbExposureLogger.log(String userId, ABTestService.Assignment assignment, String servedVariant, boolean fellBack, String modelVersion)` (non-blocking, no-op when disabled); a Spring `AsyncEventPublisher` bean named `abExposurePublisher`.

- [ ] **Step 1: Write the failing test**

```java
package com.recsys.model.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.config.ABTestConfig;
import com.recsys.online.event.AsyncEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AbExposureLoggerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ABTestConfig enabledConfig() {
        ABTestConfig c = new ABTestConfig();
        c.setEnabled(true);
        return c;
    }

    private ABTestService.Assignment assignment() {
        return new ABTestService.Assignment("test", 1234, "default", true);
    }

    @Test
    void emitsExposureEventWithServedVariant() throws Exception {
        AsyncEventPublisher publisher = mock(AsyncEventPublisher.class);
        AbExposureLogger logger = new AbExposureLogger(publisher, enabledConfig(),
                () -> "fixed-event-id", () -> 1700L);

        logger.log("123", assignment(), "test", false, "v9");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(json.capture());
        JsonNode e = mapper.readTree(json.getValue());
        assertThat(e.get("userId").asText()).isEqualTo("123");
        assertThat(e.get("assignedVariant").asText()).isEqualTo("test");
        assertThat(e.get("servedVariant").asText()).isEqualTo("test");
        assertThat(e.get("fellBackFrom").isNull()).isTrue();
        assertThat(e.get("layer").asText()).isEqualTo("default");
        assertThat(e.get("slot").asInt()).isEqualTo(1234);
        assertThat(e.get("inExperiment").asBoolean()).isTrue();
        assertThat(e.get("modelVersion").asText()).isEqualTo("v9");
        assertThat(e.get("eventId").asText()).isEqualTo("fixed-event-id");
        assertThat(e.get("timestampMs").asLong()).isEqualTo(1700L);
    }

    @Test
    void fallbackRecordsFellBackFrom() throws Exception {
        AsyncEventPublisher publisher = mock(AsyncEventPublisher.class);
        AbExposureLogger logger = new AbExposureLogger(publisher, enabledConfig(),
                () -> "id", () -> 1L);

        logger.log("123", assignment(), "training", true, "v9");  // assigned test, served training

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(json.capture());
        JsonNode e = mapper.readTree(json.getValue());
        assertThat(e.get("servedVariant").asText()).isEqualTo("training");
        assertThat(e.get("fellBackFrom").asText()).isEqualTo("test");
    }

    @Test
    void disabled_isNoOp() {
        AsyncEventPublisher publisher = mock(AsyncEventPublisher.class);
        ABTestConfig disabled = new ABTestConfig();   // enabled defaults to false
        AbExposureLogger logger = new AbExposureLogger(publisher, disabled, () -> "id", () -> 1L);

        logger.log("123", assignment(), "test", false, "v9");

        verifyNoInteractions(publisher);
    }

    @Test
    void publisherFailureDoesNotThrow() {
        AsyncEventPublisher publisher = mock(AsyncEventPublisher.class);
        when(publisher.publish(anyString())).thenThrow(new RuntimeException("boom"));
        AbExposureLogger logger = new AbExposureLogger(publisher, enabledConfig(), () -> "id", () -> 1L);

        // Must not propagate — exposure logging never breaks the request.
        logger.log("123", assignment(), "test", false, "v9");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=AbExposureLoggerTest`
Expected: COMPILATION FAILURE — `AbExposureLogger` does not exist.

- [ ] **Step 3: Create `AbExposureLogger`**

```java
package com.recsys.model.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.config.ABTestConfig;
import com.recsys.online.event.AsyncEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Emits one A/B exposure event per served recommendation request to the async event pipeline,
 * so the offline pipeline can join exposures to outcomes for lift analysis. Non-blocking and
 * best-effort: a full/failing publisher never breaks the request. No-op when A/B is disabled.
 */
@Service
public class AbExposureLogger {

    static final String TOPIC = "ab_exposures";
    private static final Logger log = LoggerFactory.getLogger(AbExposureLogger.class);

    private final AsyncEventPublisher publisher;
    private final ABTestConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Supplier<String> idGenerator;
    private final LongSupplier clock;

    @Autowired
    public AbExposureLogger(@Qualifier("abExposurePublisher") AsyncEventPublisher publisher, ABTestConfig config) {
        this(publisher, config, () -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    AbExposureLogger(AsyncEventPublisher publisher, ABTestConfig config,
                     Supplier<String> idGenerator, LongSupplier clock) {
        this.publisher = publisher;
        this.config = config;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public void log(String userId, ABTestService.Assignment assignment,
                    String servedVariant, boolean fellBack, String modelVersion) {
        if (!config.isEnabled()) {
            return;
        }
        ExposureEvent event = new ExposureEvent(
                userId,
                assignment.variant(),
                servedVariant,
                fellBack ? assignment.variant() : null,
                assignment.layerName(),
                assignment.slot(),
                assignment.inExperiment(),
                modelVersion,
                idGenerator.get(),
                clock.getAsLong());
        try {
            publisher.publish(mapper.writeValueAsString(event));
        } catch (Exception e) {   // serialization OR a misbehaving publisher — never break the request
            log.warn("failed to publish A/B exposure event for user {}", userId, e);
        }
    }

    public record ExposureEvent(String userId, String assignedVariant, String servedVariant,
                                String fellBackFrom, String layer, int slot, boolean inExperiment,
                                String modelVersion, String eventId, long timestampMs) {}
}
```

- [ ] **Step 4: Create the publisher bean**

```java
package com.recsys.model.config;

import com.recsys.online.event.AsyncEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelEventConfig {

    /** Bounded, fire-and-forget publisher for A/B exposure events. Closed on context shutdown. */
    @Bean(destroyMethod = "close")
    public AsyncEventPublisher abExposurePublisher() {
        return new AsyncEventPublisher();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=AbExposureLoggerTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/model/service/AbExposureLogger.java \
        src/main/java/com/recsys/model/config/ModelEventConfig.java \
        src/test/java/com/recsys/model/service/AbExposureLoggerTest.java
git commit -m "feat: add AbExposureLogger + exposure publisher bean

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Wire resolver + exposure into `RecommendationService` and the controller

**Files:**
- Modify: `src/main/java/com/recsys/model/converter/RecommendationConverter.java`
- Modify: `src/main/java/com/recsys/model/service/RecommendationService.java`
- Modify: `src/main/java/com/recsys/model/controller/RecommendationController.java`
- Test: `src/test/java/com/recsys/model/service/RecommendationServiceTest.java` (extend), `src/test/java/com/recsys/model/controller/RecommendationControllerTest.java` (extend)

**Interfaces:**
- Consumes: `VariantRuntimeResolver.resolve` (Task 3), `ABTestService.defaultVariant()` (Task 2), `AbExposureLogger.log` (Task 4).
- Produces: `RecommendResponse.abTestVariant()` = served variant; cache keyed on served variant; one exposure event per served response.

- [ ] **Step 1: Change `RecommendationConverter.toResponse` to take the variant string**

Replace the method signature/body:
```java
    public RecommendResponse toResponse(
            RecommendRequest request,
            String modelVersion,
            String variant,
            List<ScoredItem> items
    ) {
        return new RecommendResponse(request.getUserId(), modelVersion, variant, items);
    }
```
(Remove the now-unused `import com.recsys.model.service.ABTestService;` if present.)

- [ ] **Step 2: Wire the resolver into `RecommendationService`**

(a) Add fields + import:
```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
...
    private final VariantRuntimeResolver variantRuntimeResolver;
```
(b) Add `variantRuntimeResolver` as the last param of the `@Autowired` constructor and assign it; in each non-`@Autowired` convenience constructor, pass a default `new VariantRuntimeResolver(modelRuntimeProvider, new SimpleMeterRegistry())` through the chain so existing 2/3/4/5-arg callers keep working. (Add a 6-arg `@Autowired` constructor; the 5-arg one delegates to it with the default resolver.)

(c) In `recommend(RecommendRequest request, ABTestService.Assignment assignment)`, replace:
```java
        ModelRuntime runtime = modelRuntimeProvider.getRuntime(assignment.variant());
        String modelVersion = runtime.modelVersion();
```
with:
```java
        VariantRuntimeResolver.Resolved resolved =
                variantRuntimeResolver.resolve(assignment.variant(), abTestService.defaultVariant());
        ModelRuntime runtime = resolved.runtime();
        String servedVariant = resolved.servedVariant();
        String modelVersion = runtime.modelVersion();
```
Then use `servedVariant` in the cache key and the response: change the `RecommendationKey` `assignment.variant()` argument to `servedVariant`, and change the final `converter.toResponse(request, modelVersion, assignment, items)` to `converter.toResponse(request, modelVersion, servedVariant, items)`.

(d) In `tryServeFromCache(request, assignment)`, the runtime comes from `getLoadedRuntime(assignment.variant())` (unchanged — degraded path does not build/fallback); change its two `converter.toResponse(request, modelVersion, assignment, ...)` calls to pass `assignment.variant()` (the served variant on this path).

- [ ] **Step 3: Extend `RecommendationServiceTest`** — add a test that a fallback served-variant flows to the response + cache key, constructing the service with a mocked resolver via the full constructor:

```java
    @Test
    void servedVariantFromResolverDrivesResponse() {
        VariantRuntimeResolver resolver = mock(VariantRuntimeResolver.class);
        when(resolver.resolve(eq("test"), any()))
                .thenReturn(new VariantRuntimeResolver.Resolved(runtime, "training", true));
        when(abTestService.defaultVariant()).thenReturn("training");
        when(abTestService.getAssignmentForUser(any()))
                .thenReturn(new ABTestService.Assignment("test", 10, "default", true));
        // Build the service with the mocked resolver (full constructor).
        RecommendationService svc = new RecommendationService(
                modelRuntimeProvider, abTestService, new RecommendationCacheProperties(),
                /* featureFlagService */ org.mockito.Mockito.mock(com.recsys.featureflags.FeatureFlagService.class),
                new com.recsys.model.converter.RecommendationConverter(), resolver);
        // runtime mock returns a model version + the rank produces some items (reuse the existing stubs).

        RecommendResponse response = svc.recommend(request("123", 5));

        assertThat(response.abTestVariant()).isEqualTo("training");   // served, not assigned
    }
```
(Adapt the `runtime`/stubs to whatever the existing `setUp` already wires so `recommend` returns items; the assertion of interest is `abTestVariant == "training"`. If the existing FeatureFlagService stub differs, reuse it.)

Existing tests keep passing: the 2-arg constructor builds a default resolver around the mocked `modelRuntimeProvider`, which returns the stubbed runtime, so `servedVariant == assignment.variant()` and behavior is unchanged.

- [ ] **Step 4: Wire exposure logging into `RecommendationController`**

(a) Inject `AbExposureLogger abExposureLogger` via the constructor (add the field + parameter, assign it).
(b) On the **normal** success path, right after `metricsService.recordSuccess(...)`, add:
```java
            abExposureLogger.log(request.getUserId(), assignment, response.abTestVariant(),
                    !response.abTestVariant().equals(assignment.variant()), response.modelVersion());
```
(c) On the **degraded-cache** success path, right before returning the degraded response, add the same call using the cached `fallback.get()`:
```java
            RecommendResponse degraded = fallback.get();
            abExposureLogger.log(request.getUserId(), assignment, degraded.abTestVariant(),
                    !degraded.abTestVariant().equals(assignment.variant()), degraded.modelVersion());
```
(restructure the `fallback.isPresent()` block to name the response, then log, then return).

- [ ] **Step 5: Extend `RecommendationControllerTest`** — verify one exposure per served request, with the served variant. Add (mocking `AbExposureLogger` in the controller's deps):

```java
    @Test
    void emitsExposureWithServedVariantOnSuccess() {
        when(abTestService.getAssignmentForUser(any()))
                .thenReturn(new ABTestService.Assignment("test", 10, "default", true));
        when(loadShedder.tryAcquire()).thenReturn(true);
        when(recommendationService.recommend(any(), any()))
                .thenReturn(new RecommendResponse("123", "v9", "training", java.util.List.of())); // fell back to control
        when(loadShedder.snapshot()).thenReturn(/* existing snapshot stub */);

        controller.recommend(request, null);

        verify(abExposureLogger).log(eq("123"), any(), eq("training"), eq(true), eq("v9"));
    }
```
(Adapt mock field names to the existing test's setup; add `abExposureLogger = mock(AbExposureLogger.class)` to the controller construction in `setUp`.)

- [ ] **Step 6: Compile + run the touched suites**

Run: `mvn -q -DskipTests compile`
Then: `mvn test -Dtest=RecommendationConverterTest,RecommendationServiceTest,RecommendationControllerTest,RecommendationControllerRegressionTest,ModelV2RecommendIntegrationTest,RecommendationEndToEndTest`
Expected: PASS. (Integration tests run with A/B disabled by default → no exposure events, no fallback; the new beans component-scan cleanly.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/model/converter/RecommendationConverter.java \
        src/main/java/com/recsys/model/service/RecommendationService.java \
        src/main/java/com/recsys/model/controller/RecommendationController.java \
        src/test/java/com/recsys/model/service/RecommendationServiceTest.java \
        src/test/java/com/recsys/model/controller/RecommendationControllerTest.java
git commit -m "feat: serve-variant response/cache + per-request A/B exposure logging

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Full-suite + config-load verification

**Files:** none (verification only).

- [ ] **Step 1: Full build + test suite**

Run: `mvn test`
Expected: BUILD SUCCESS — all tests pass; the Spring context loads with the new `bucket-a-percent`/`bucket-b-percent` properties and the `abExposurePublisher` bean (proves the `application.yml` migration + bean wiring are valid).

- [ ] **Step 2: Confirm no stale references**

Run: `grep -rn "trafficSplitNumber\|getTrafficSplitNumber\|\.bucket()" src/main/java src/test/java`
Expected: no matches (all replaced by percents / `slot()`).

- [ ] **Step 3: No commit** (verification only).

---

## Self-Review

**Spec coverage:**
- §4.1 `StableBucketer` + range allocation → Task 1 (slot) + Task 2 (ranges in ABTestService).
- §4.2 `ABTestConfig` percents + drop volatile → Task 2.
- §4.3 `ABTestService` slot-based + snapshot + `Assignment.slot` + `defaultVariant()` → Task 2.
- §4.4 `VariantRuntimeResolver` fallback + cooldown + metric → Task 3.
- §4.5 `AbExposureLogger` + `ExposureEvent` + async publish + disabled no-op → Task 4.
- §4.6 controller resolve/exposure + service served-variant cache/response → Task 5.
- §5/§6 behavior + error handling (disabled byte-identical; broken variant→control; broken control→propagate; publisher full→drop) → Tasks 3,4,5.
- §7 testing → Tasks 1–6.
- §9 files changed → all covered; `application.yml` migration → Task 2 Step 4.

**Placeholder scan:** none — every step has concrete code/commands. Test steps that say "adapt to the existing setUp" name the exact assertion of interest and the exact new mock; this is necessary adaptation to a file the implementer reads, not a placeholder.

**Type consistency:**
- `StableBucketer.slot(String,String)` / `KEYSPACE` — defined Task 1, used Task 2.
- `Assignment(String variant, int slot, String layerName, boolean inExperiment)` + `control(variant,layer)` — defined Task 2, used Tasks 4,5.
- `ABTestConfig.getBucketAPercent/getBucketBPercent` — defined Task 2, used Task 2 (ABTestService).
- `ABTestService.defaultVariant()` — defined Task 2, used Tasks 3-test,5.
- `VariantRuntimeResolver.resolve(String,String)` → `Resolved(ModelRuntime,String,boolean)` — defined Task 3, used Task 5.
- `AbExposureLogger.log(String, Assignment, String, boolean, String)` — defined Task 4, used Task 5.
- `RecommendationConverter.toResponse(request, modelVersion, String variant, items)` — changed Task 5 Step 1, used Task 5 Step 2.
- `abExposurePublisher` bean (`@Qualifier`) — defined Task 4, injected Task 4 (`AbExposureLogger`).
