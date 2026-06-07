package com.recsys.featureflags;

import com.recsys.featureflags.models.FeatureFlag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFlagServiceTest {

    @Test
    void returnsResolvedProviderValue() {
        FeatureFlagService service = new FeatureFlagService((flag, distinctId, properties) -> Optional.of(false));

        assertThat(service.isEnabled(FeatureFlag.enabledByDefault("new-ranking"), "user-1")).isFalse();
    }

    @Test
    void returnsFlagDefaultWhenProviderCannotResolve() {
        FeatureFlagService service = new FeatureFlagService((flag, distinctId, properties) -> Optional.empty());

        assertThat(service.isEnabled(FeatureFlag.enabledByDefault("new-ranking"))).isTrue();
        assertThat(service.isEnabled(FeatureFlag.disabledByDefault("new-ranking"))).isFalse();
    }

    @Test
    void passesEvaluationContextToProvider() {
        FeatureFlagService service = new FeatureFlagService((flag, distinctId, properties) ->
                Optional.of("user-1".equals(distinctId) && "pro".equals(properties.get("plan"))));

        assertThat(service.isEnabled(
                FeatureFlag.disabledByDefault("new-ranking"),
                "user-1",
                Map.of("plan", "pro")))
                .isTrue();
    }
}
