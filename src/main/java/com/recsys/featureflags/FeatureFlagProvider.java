package com.recsys.featureflags;

import com.recsys.featureflags.models.FeatureFlag;

import java.util.Map;
import java.util.Optional;

@FunctionalInterface
public interface FeatureFlagProvider {

    Optional<Boolean> resolve(FeatureFlag flag, String distinctId, Map<String, Object> properties);
}
