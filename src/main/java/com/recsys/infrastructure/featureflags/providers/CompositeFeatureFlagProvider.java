package com.recsys.infrastructure.featureflags.providers;

import com.recsys.infrastructure.featureflags.FeatureFlagProvider;
import com.recsys.infrastructure.featureflags.models.FeatureFlag;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class CompositeFeatureFlagProvider implements FeatureFlagProvider {

    private final List<FeatureFlagProvider> providers;

    public CompositeFeatureFlagProvider(List<FeatureFlagProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    public CompositeFeatureFlagProvider(FeatureFlagProvider... providers) {
        this(List.of(providers));
    }

    @Override
    public Optional<Boolean> resolve(FeatureFlag flag, String distinctId, Map<String, Object> properties) {
        for (FeatureFlagProvider provider : providers) {
            Optional<Boolean> result = provider.resolve(flag, distinctId, properties);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }
}
