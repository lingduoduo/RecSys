package com.recsys.infrastructure.featureflags;

import com.recsys.infrastructure.featureflags.models.FeatureFlag;

import java.util.Map;
import java.util.Objects;

public class FeatureFlagService {

    private final FeatureFlagProvider provider;

    public FeatureFlagService(FeatureFlagProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public boolean isEnabled(FeatureFlag flag) {
        return isEnabled(flag, null, Map.of());
    }

    public boolean isEnabled(FeatureFlag flag, String distinctId) {
        return isEnabled(flag, distinctId, Map.of());
    }

    public boolean isEnabled(FeatureFlag flag, String distinctId, Map<String, Object> properties) {
        Objects.requireNonNull(flag, "flag");
        Map<String, Object> safeProperties = properties == null ? Map.of() : Map.copyOf(properties);
        return provider.resolve(flag, distinctId, safeProperties).orElse(flag.defaultEnabled());
    }
}
