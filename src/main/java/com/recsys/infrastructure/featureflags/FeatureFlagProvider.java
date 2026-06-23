package com.recsys.infrastructure.featureflags;

import com.recsys.infrastructure.featureflags.models.FeatureFlag;

import java.util.Map;
import java.util.Optional;

@FunctionalInterface
public interface FeatureFlagProvider {

    Optional<Boolean> resolve(FeatureFlag flag, String distinctId, Map<String, Object> properties);
}
