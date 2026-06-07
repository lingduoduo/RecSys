package com.recsys.featureflags.providers;

import com.recsys.featureflags.FeatureFlagProvider;
import com.recsys.featureflags.models.FeatureFlag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeFeatureFlagProviderTest {

    @Test
    void usesFirstResolvedValueIncludingFalse() {
        AtomicBoolean fallbackCalled = new AtomicBoolean();
        FeatureFlagProvider unresolved = (flag, distinctId, properties) -> Optional.empty();
        FeatureFlagProvider disabled = (flag, distinctId, properties) -> Optional.of(false);
        FeatureFlagProvider fallback = (flag, distinctId, properties) -> {
            fallbackCalled.set(true);
            return Optional.of(true);
        };

        CompositeFeatureFlagProvider provider =
                new CompositeFeatureFlagProvider(unresolved, disabled, fallback);

        assertThat(provider.resolve(
                FeatureFlag.enabledByDefault("new-ranking"),
                "user-1",
                Map.of()))
                .contains(false);
        assertThat(fallbackCalled).isFalse();
    }
}
