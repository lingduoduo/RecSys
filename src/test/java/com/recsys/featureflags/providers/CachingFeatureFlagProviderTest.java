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
