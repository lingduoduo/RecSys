package com.recsys.modelbased.twotower.service;

import com.recsys.modelbased.twotower.config.ABTestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ABTestService {

    private static final Logger log = LoggerFactory.getLogger(ABTestService.class);

    private final ABTestConfig config;

    public ABTestService(ABTestConfig config) {
        this.config = config;
    }

    /**
     * Assigns a user to a variant using the layer name configured in {@link ABTestConfig}.
     * Shorthand for {@link #getVariantForUser(String, String)}.
     */
    public String getVariantForUser(String userId) {
        return getVariantForUser(userId, config.getLayerName());
    }

    /**
     * Deterministically assigns a user to a variant for the given layer.
     * The hash key is {@code userId + ":" + layerName}, so two layers with different names
     * produce independent bucket assignments for the same user.
     *
     * Bucket 0 → variant A, bucket 1 → variant B, all others → default (control).
     * Returns the default variant when A/B testing is disabled or userId is blank.
     */
    public String getVariantForUser(String userId, String layerName) {
        if (!config.isEnabled() || userId == null || userId.isBlank()) {
            return config.getDefaultVariant();
        }
        // Mix userId + layerName so each layer produces an independent bucketing.
        // Mask the sign bit so modulo always yields a non-negative bucket index.
        String key = userId + ":" + layerName;
        int bucket = (key.hashCode() & Integer.MAX_VALUE) % config.getTrafficSplitNumber();
        if (bucket == 0) {
            log.info("user {} in layer '{}' bucketed into A ({})", userId, layerName, config.getBucketAVariant());
            return config.getBucketAVariant();
        }
        if (bucket == 1) {
            log.info("user {} in layer '{}' bucketed into B ({})", userId, layerName, config.getBucketBVariant());
            return config.getBucketBVariant();
        }
        return config.getDefaultVariant();
    }
}
