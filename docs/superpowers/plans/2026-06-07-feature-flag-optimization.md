# Feature Flag Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate per-request PostHog HTTP calls by adding a TTL cache layer, and wire the dormant `FeatureFlagService` into the recommendation serving path so flags actually gate behavior.

**Architecture:** A new `CachingFeatureFlagProvider` wraps the `PostHogFeatureFlagProvider` and memoizes results per `(flagKey, distinctId)` for a configurable TTL using a `ConcurrentHashMap`. `FeatureFlagConfig` wraps the PostHog provider in this cache automatically. A `Flags` constants class defines known flags centrally; `RecommendationService` receives `FeatureFlagService` via its `@Autowired` constructor and uses `Flags.COLD_START_ENABLED` to gate the cold-start inference path at runtime.

**Tech Stack:** Java 17, JUnit 5 (AssertJ), Spring Boot 3.3.4, `ConcurrentHashMap` with nano-time expiry (no new dependencies)

---

## File Structure

**New files:**
- `src/main/java/com/recsys/featureflags/providers/CachingFeatureFlagProvider.java` — TTL cache wrapper around any `FeatureFlagProvider`
- `src/main/java/com/recsys/featureflags/Flags.java` — Central registry of known `FeatureFlag` constants
- `src/test/java/com/recsys/featureflags/providers/CachingFeatureFlagProviderTest.java` — Unit tests for caching semantics

**Modified files:**
- `src/main/java/com/recsys/featureflags/config/FeatureFlagConfig.java` — Add `cacheTtl` property; wrap PostHog in `CachingFeatureFlagProvider`
- `src/test/java/com/recsys/featureflags/config/FeatureFlagConfigTest.java` — Add test verifying `cacheTtl` binding
- `src/main/resources/application.yml` — Add `post-hog.cache-ttl` env-var-backed default
- `src/main/java/com/recsys/modelbased/service/RecommendationService.java` — Add `FeatureFlagService` to constructor; gate cold-start behind `Flags.COLD_START_ENABLED`
- `src/test/java/com/recsys/modelbased/service/RecommendationServiceTest.java` — Add test verifying flag disables cold-start path

---

## Task 1: `CachingFeatureFlagProvider`

**Files:**
- Create: `src/main/java/com/recsys/featureflags/providers/CachingFeatureFlagProvider.java`
- Create: `src/test/java/com/recsys/featureflags/providers/CachingFeatureFlagProviderTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/recsys/featureflags/providers/CachingFeatureFlagProviderTest.java`:

```java
package com.recsys.featureflags.providers;

import com.recsys.featureflags.models.FeatureFlag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CachingFeatureFlagProviderTest {

    private final FeatureFlag FLAG = FeatureFlag.disabledByDefault("test-flag");

    @Test
    void cachedResultReturnedWithoutCallingDelegateAgain() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        CachingFeatureFlagProvider provider = new CachingFeatureFlagProvider(
                (f, id, props) -> { calls.incrementAndGet(); return Optional.of(true); },
                Duration.ofSeconds(60),
                clock::get);

        Optional<Boolean> first = provider.resolve(FLAG, "user-1", Map.of());
        Optional<Boolean> second = provider.resolve(FLAG, "user-1", Map.of());

        assertThat(first).contains(true);
        assertThat(second).contains(true);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void expiredEntryTriggersDelegateAgain() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        CachingFeatureFlagProvider provider = new CachingFeatureFlagProvider(
                (f, id, props) -> { calls.incrementAndGet(); return Optional.of(false); },
                Duration.ofSeconds(1),
                clock::get);

        provider.resolve(FLAG, "user-1", Map.of());
        clock.set(Duration.ofSeconds(2).toNanos());
        provider.resolve(FLAG, "user-1", Map.of());

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void differentUsersAreCachedSeparately() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        CachingFeatureFlagProvider provider = new CachingFeatureFlagProvider(
                (f, id, props) -> { calls.incrementAndGet(); return Optional.of(true); },
                Duration.ofSeconds(60),
                clock::get);

        provider.resolve(FLAG, "user-1", Map.of());
        provider.resolve(FLAG, "user-2", Map.of());
        provider.resolve(FLAG, "user-1", Map.of());

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void emptyDelegateResponseIsCachedToAvoidRepeatCalls() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        CachingFeatureFlagProvider provider = new CachingFeatureFlagProvider(
                (f, id, props) -> { calls.incrementAndGet(); return Optional.empty(); },
                Duration.ofSeconds(60),
                clock::get);

        Optional<Boolean> first = provider.resolve(FLAG, "user-1", Map.of());
        Optional<Boolean> second = provider.resolve(FLAG, "user-1", Map.of());

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void evictExpiredCausesNextCallToHitDelegate() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        CachingFeatureFlagProvider provider = new CachingFeatureFlagProvider(
                (f, id, props) -> { calls.incrementAndGet(); return Optional.of(true); },
                Duration.ofSeconds(1),
                clock::get);

        provider.resolve(FLAG, "user-1", Map.of());
        clock.set(Duration.ofSeconds(2).toNanos());
        provider.evictExpired();
        provider.resolve(FLAG, "user-1", Map.of());

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void nullDistinctIdIsCachedSeparatelyFromNamed() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        CachingFeatureFlagProvider provider = new CachingFeatureFlagProvider(
                (f, id, props) -> { calls.incrementAndGet(); return Optional.of(true); },
                Duration.ofSeconds(60),
                clock::get);

        provider.resolve(FLAG, null, Map.of());
        provider.resolve(FLAG, null, Map.of());
        provider.resolve(FLAG, "user-1", Map.of());

        assertThat(calls.get()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd /Users/linghuang/Git/Recsys-Backend-Service
mvn test -Dtest=CachingFeatureFlagProviderTest -pl . 2>&1 | tail -20
```

Expected: compilation error — `CachingFeatureFlagProvider` does not exist.

- [ ] **Step 3: Implement `CachingFeatureFlagProvider`**

Create `src/main/java/com/recsys/featureflags/providers/CachingFeatureFlagProvider.java`:

```java
package com.recsys.featureflags.providers;

import com.recsys.featureflags.FeatureFlagProvider;
import com.recsys.featureflags.models.FeatureFlag;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public class CachingFeatureFlagProvider implements FeatureFlagProvider {

    private final FeatureFlagProvider delegate;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public CachingFeatureFlagProvider(FeatureFlagProvider delegate, Duration ttl) {
        this(delegate, ttl, System::nanoTime);
    }

    CachingFeatureFlagProvider(FeatureFlagProvider delegate, Duration ttl, LongSupplier clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ttlNanos = Objects.requireNonNull(ttl, "ttl").toNanos();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<Boolean> resolve(FeatureFlag flag, String distinctId, Map<String, Object> properties) {
        CacheKey key = new CacheKey(flag.key(), distinctId);
        long nowNanos = clock.getAsLong();
        CacheEntry existing = cache.get(key);
        if (existing != null && nowNanos < existing.expiryNanos()) {
            return Optional.ofNullable(existing.value());
        }
        Optional<Boolean> result = delegate.resolve(flag, distinctId, properties);
        cache.put(key, new CacheEntry(result.orElse(null), nowNanos + ttlNanos));
        return result;
    }

    /** Removes all expired entries. Call periodically to bound memory when user cardinality is high. */
    public void evictExpired() {
        long nowNanos = clock.getAsLong();
        cache.entrySet().removeIf(e -> nowNanos >= e.getValue().expiryNanos());
    }

    private record CacheKey(String flagKey, String distinctId) {}
    private record CacheEntry(Boolean value, long expiryNanos) {}
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
mvn test -Dtest=CachingFeatureFlagProviderTest -pl .
```

Expected: `BUILD SUCCESS`, all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/recsys/featureflags/providers/CachingFeatureFlagProvider.java \
        src/test/java/com/recsys/featureflags/providers/CachingFeatureFlagProviderTest.java
git commit -m "$(cat <<'EOF'
feat: add CachingFeatureFlagProvider to eliminate per-request PostHog HTTP calls

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Wire caching into `FeatureFlagConfig`

**Files:**
- Modify: `src/main/java/com/recsys/featureflags/config/FeatureFlagConfig.java`
- Modify: `src/test/java/com/recsys/featureflags/config/FeatureFlagConfigTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write a failing test for `cacheTtl` binding**

Add to `FeatureFlagConfigTest.java` (inside the class, after the existing two tests):

```java
@Test
void bindsPostHogCacheTtl() {
    contextRunner
            .withPropertyValues(
                    "recsys.feature-flags.post-hog.enabled=true",
                    "recsys.feature-flags.post-hog.api-key=phc_test",
                    "recsys.feature-flags.post-hog.cache-ttl=30s")
            .run(context -> {
                FeatureFlagConfig.Properties config = context.getBean(FeatureFlagConfig.Properties.class);
                assertThat(config.getPostHog().getCacheTtl())
                        .isEqualTo(java.time.Duration.ofSeconds(30));
            });
}
```

- [ ] **Step 2: Run to confirm the new test fails**

```bash
mvn test -Dtest=FeatureFlagConfigTest -pl .
```

Expected: FAIL — `getCacheTtl()` does not exist on `PostHog`.

- [ ] **Step 3: Add `cacheTtl` to `FeatureFlagConfig.PostHog` and update the bean**

Replace the entire `FeatureFlagConfig.java` with:

```java
package com.recsys.featureflags.config;

import com.recsys.featureflags.FeatureFlagProvider;
import com.recsys.featureflags.FeatureFlagService;
import com.recsys.featureflags.providers.CachingFeatureFlagProvider;
import com.recsys.featureflags.providers.CompositeFeatureFlagProvider;
import com.recsys.featureflags.providers.EnvFeatureFlagProvider;
import com.recsys.featureflags.providers.PostHogFeatureFlagProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeatureFlagConfig.Properties.class)
public class FeatureFlagConfig {

    @Bean
    public FeatureFlagProvider featureFlagProvider(Properties properties) {
        List<FeatureFlagProvider> providers = new ArrayList<>();
        providers.add(new EnvFeatureFlagProvider(properties.environmentPrefix));
        PostHog postHog = properties.postHog;
        if (postHog.enabled && postHog.apiKey != null && !postHog.apiKey.isBlank()) {
            FeatureFlagProvider raw = new PostHogFeatureFlagProvider(
                    postHog.apiKey, postHog.host, postHog.timeout);
            providers.add(new CachingFeatureFlagProvider(raw, postHog.cacheTtl));
        }
        return new CompositeFeatureFlagProvider(providers);
    }

    @Bean
    public FeatureFlagService featureFlagService(FeatureFlagProvider featureFlagProvider) {
        return new FeatureFlagService(featureFlagProvider);
    }

    @ConfigurationProperties(prefix = "recsys.feature-flags")
    public static class Properties {
        private String environmentPrefix = "FEATURE_FLAG_";
        private final PostHog postHog = new PostHog();

        public String getEnvironmentPrefix() { return environmentPrefix; }
        public void setEnvironmentPrefix(String environmentPrefix) { this.environmentPrefix = environmentPrefix; }
        public PostHog getPostHog() { return postHog; }
    }

    public static class PostHog {
        private boolean enabled;
        private String apiKey;
        private URI host = URI.create("https://us.i.posthog.com");
        private Duration timeout = Duration.ofSeconds(2);
        private Duration cacheTtl = Duration.ofMinutes(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public URI getHost() { return host; }
        public void setHost(URI host) { this.host = host; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public Duration getCacheTtl() { return cacheTtl; }
        public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
    }
}
```

- [ ] **Step 4: Add `cache-ttl` to `application.yml`**

In `src/main/resources/application.yml`, find the `post-hog:` block under `recsys.feature-flags:` and add the `cache-ttl` line:

```yaml
    post-hog:
      enabled: ${POSTHOG_FEATURE_FLAGS_ENABLED:false}
      api-key: ${POSTHOG_PROJECT_API_KEY:}
      host: ${POSTHOG_HOST:https://us.i.posthog.com}
      timeout: ${POSTHOG_FEATURE_FLAGS_TIMEOUT:2s}
      cache-ttl: ${POSTHOG_FEATURE_FLAGS_CACHE_TTL:60s}
```

- [ ] **Step 5: Run all feature flag tests to confirm they pass**

```bash
mvn test -Dtest="FeatureFlagConfigTest,CachingFeatureFlagProviderTest,FeatureFlagServiceTest,CompositeFeatureFlagProviderTest,EnvFeatureFlagProviderTest,PostHogFeatureFlagProviderTest" -pl .
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/recsys/featureflags/config/FeatureFlagConfig.java \
        src/test/java/com/recsys/featureflags/config/FeatureFlagConfigTest.java \
        src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat: wrap PostHog provider in CachingFeatureFlagProvider with configurable TTL

Defaults to 60 s. Override via POSTHOG_FEATURE_FLAGS_CACHE_TTL.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `Flags` constants registry

**Files:**
- Create: `src/main/java/com/recsys/featureflags/Flags.java`

- [ ] **Step 1: Create `Flags.java`**

```java
package com.recsys.featureflags;

import com.recsys.featureflags.models.FeatureFlag;

public final class Flags {
    private Flags() {}

    /**
     * Gates the cold-start inference path for users absent from the training vocabulary.
     * Set FEATURE_FLAG_COLD_START_ENABLED=false to disable at the instance level without redeploying.
     */
    public static final FeatureFlag COLD_START_ENABLED = FeatureFlag.enabledByDefault("cold-start-enabled");

    /**
     * Enables the new-ranking model variant; used for gradual rollout before wiring into ABTestService.
     * Set FEATURE_FLAG_NEW_RANKING=true or configure via PostHog.
     */
    public static final FeatureFlag NEW_RANKING = FeatureFlag.disabledByDefault("new-ranking");
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn compile -pl . -q
```

Expected: `BUILD SUCCESS` with no output.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/recsys/featureflags/Flags.java
git commit -m "$(cat <<'EOF'
feat: add Flags constants class as central registry of known feature flags

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Wire `FeatureFlagService` into `RecommendationService`

**Files:**
- Modify: `src/main/java/com/recsys/modelbased/service/RecommendationService.java`
- Modify: `src/test/java/com/recsys/modelbased/service/RecommendationServiceTest.java`

- [ ] **Step 1: Write a new failing test that verifies the flag disables cold-start**

Add to `RecommendationServiceTest.java` (after the existing `recommend_unknownUsers_shareColdStartCache` test, inside the class):

```java
@Test
void recommend_coldStartFlagDisabled_runsFullInferenceForEachUnknownUser() {
    var flagService = new com.recsys.featureflags.FeatureFlagService(
            (flag, id, props) -> "cold-start-enabled".equals(flag.key())
                    ? Optional.of(false)
                    : Optional.empty());
    var testService = new RecommendationService(
            modelRuntimeProvider, abTestService,
            new com.recsys.modelbased.config.RecommendationCacheProperties(),
            flagService);

    var encoded = new FeatureEncoder.EncodedFeatures(0L);
    when(artifactService.getUserVocab()).thenReturn(Map.of("__UNK__", 0));
    when(featureEncoder.encode(any())).thenReturn(encoded);
    when(candidateSelectionService.selectCandidates(any(), any())).thenReturn(Set.of("1", "2"));
    when(inferenceService.scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt()))
            .thenReturn(List.of(new ScoredItem("1", 0.9), new ScoredItem("2", 0.8)));
    when(artifactService.getModelVersion()).thenReturn("v1");

    testService.recommend(request("new-user-a", 1));
    testService.recommend(request("new-user-b", 1));

    // flag disabled → no cold-start pool → each unknown user triggers its own inference call
    verify(inferenceService, times(2)).scoreCandidates(eq(encoded), eq(featureEncoder), any(), anyInt());
}
```

You also need `import java.util.Optional;` at the top of the test file if it is not already present.

- [ ] **Step 2: Run to confirm the new test fails**

```bash
mvn test -Dtest=RecommendationServiceTest#recommend_coldStartFlagDisabled_runsFullInferenceForEachUnknownUser -pl .
```

Expected: compilation error — `RecommendationService` has no 4-argument constructor.

- [ ] **Step 3: Update `RecommendationService`**

Replace the constructor block and add the field. The full diff is as follows. At the top of the class, add the import and the field:

```java
import com.recsys.featureflags.Flags;
import com.recsys.featureflags.FeatureFlagService;
import java.util.Optional;
```

Add the field after `private final RecommendationCache cache;`:

```java
private final FeatureFlagService featureFlagService;

private static final FeatureFlagService NOOP_FLAGS =
        new FeatureFlagService((flag, id, props) -> Optional.empty());
```

Replace the two constructors with three constructors:

```java
public RecommendationService(
        ModelRuntimeProvider modelRuntimeProvider,
        ABTestService abTestService
) {
    this(modelRuntimeProvider, abTestService, new RecommendationCacheProperties(), NOOP_FLAGS);
}

public RecommendationService(
        ModelRuntimeProvider modelRuntimeProvider,
        ABTestService abTestService,
        RecommendationCacheProperties cacheProperties
) {
    this(modelRuntimeProvider, abTestService, cacheProperties, NOOP_FLAGS);
}

@Autowired
public RecommendationService(
        ModelRuntimeProvider modelRuntimeProvider,
        ABTestService abTestService,
        RecommendationCacheProperties cacheProperties,
        FeatureFlagService featureFlagService
) {
    this.modelRuntimeProvider = modelRuntimeProvider;
    this.abTestService = abTestService;
    this.cache = new RecommendationCache(cacheProperties);
    this.featureFlagService = featureFlagService;
}
```

Move the `@Autowired` annotation from the old 3-parameter constructor to the new 4-parameter constructor (so Spring autowires `FeatureFlagService` from the context).

- [ ] **Step 4: Gate cold-start behind the feature flag**

In `RecommendationService.recommend(RecommendRequest, Assignment)`, locate this line:

```java
if (cache.isColdStartEnabled() && isColdStartUser(request.getUserId(), runtime)) {
```

Replace it with:

```java
if (cache.isColdStartEnabled()
        && featureFlagService.isEnabled(Flags.COLD_START_ENABLED, request.getUserId())
        && isColdStartUser(request.getUserId(), runtime)) {
```

- [ ] **Step 5: Run all `RecommendationServiceTest` tests**

```bash
mvn test -Dtest=RecommendationServiceTest -pl .
```

Expected: `BUILD SUCCESS`, all tests pass. The existing `recommend_unknownUsers_shareColdStartCache` test still passes because `NOOP_FLAGS` falls back to `Flags.COLD_START_ENABLED.defaultEnabled()` which is `true`.

- [ ] **Step 6: Run the full test suite to catch regressions**

```bash
mvn test -pl .
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/recsys/modelbased/service/RecommendationService.java \
        src/test/java/com/recsys/modelbased/service/RecommendationServiceTest.java
git commit -m "$(cat <<'EOF'
feat: wire FeatureFlagService into RecommendationService; gate cold-start via Flags.COLD_START_ENABLED

Existing static cache property still gates the path; the flag adds a runtime override
controllable via FEATURE_FLAG_COLD_START_ENABLED env var or PostHog without redeploying.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```
