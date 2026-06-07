package com.recsys.featureflags.providers;

import com.recsys.featureflags.models.FeatureFlag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvFeatureFlagProviderTest {

    @Test
    void normalizesFlagKeyAndParsesEnabledValue() {
        EnvFeatureFlagProvider provider = new EnvFeatureFlagProvider(
                "FF_",
                Map.of("FF_NEW_RANKING_V2", "yes")::get);

        assertThat(provider.resolve(
                FeatureFlag.disabledByDefault("new-rankingV2"),
                null,
                Map.of()))
                .contains(true);
    }

    @Test
    void parsesDisabledValue() {
        EnvFeatureFlagProvider provider = new EnvFeatureFlagProvider(
                "FEATURE_FLAG_",
                Map.of("FEATURE_FLAG_NEW_RANKING", "off")::get);

        assertThat(provider.resolve(
                FeatureFlag.enabledByDefault("new-ranking"),
                null,
                Map.of()))
                .contains(false);
    }

    @Test
    void missingOrInvalidValueIsUnresolved() {
        EnvFeatureFlagProvider provider = new EnvFeatureFlagProvider(
                "FEATURE_FLAG_",
                Map.of("FEATURE_FLAG_INVALID", "sometimes")::get);

        assertThat(provider.resolve(FeatureFlag.disabledByDefault("missing"), null, Map.of())).isEmpty();
        assertThat(provider.resolve(FeatureFlag.disabledByDefault("invalid"), null, Map.of())).isEmpty();
    }
}
