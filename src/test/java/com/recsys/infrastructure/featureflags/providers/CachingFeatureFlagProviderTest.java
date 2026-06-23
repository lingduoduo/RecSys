package com.recsys.infrastructure.featureflags.providers;

import com.recsys.infrastructure.featureflags.FeatureFlagProvider;
import com.recsys.infrastructure.featureflags.models.FeatureFlag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingFeatureFlagProviderTest {

    private static final FeatureFlag FLAG = FeatureFlag.disabledByDefault("test-flag");

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

    @Test
    void zeroOrNegativeTtlThrowsIllegalArgument() {
        FeatureFlagProvider noop = (f, id, props) -> Optional.empty();
        assertThatThrownBy(() -> new CachingFeatureFlagProvider(noop, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl must be positive");
        assertThatThrownBy(() -> new CachingFeatureFlagProvider(noop, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl must be positive");
    }
}
