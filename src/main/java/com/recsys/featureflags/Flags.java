package com.recsys.featureflags;

import com.recsys.featureflags.models.FeatureFlag;

public final class Flags {
    private Flags() {}

    /**
     * Gates the cold-start inference path for users absent from the training vocabulary.
     * Set FEATURE_FLAG_COLD_START_ENABLED=false to disable at the instance level without redeploying.
     */
    public static final FeatureFlag COLD_START_ENABLED = FeatureFlag.enabledByDefault("cold-start-enabled");

    /**
     * Enables the new-ranking model variant; used for gradual rollout before wiring into ABTestService.
     * Set FEATURE_FLAG_NEW_RANKING=true or configure via PostHog.
     */
    public static final FeatureFlag NEW_RANKING = FeatureFlag.disabledByDefault("new-ranking");
}
