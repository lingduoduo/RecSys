package com.recsys.infrastructure.featureflags.models;

import java.util.Objects;

public record FeatureFlag(String key, boolean defaultEnabled) {

    public FeatureFlag {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Feature flag key must not be blank");
        }
    }

    public static FeatureFlag disabledByDefault(String key) {
        return new FeatureFlag(key, false);
    }

    public static FeatureFlag enabledByDefault(String key) {
        return new FeatureFlag(key, true);
    }
}
