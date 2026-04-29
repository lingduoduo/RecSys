package com.recsys.modelbased.model.service;

import com.recsys.modelbased.model.config.ABTestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ABTestService {

    private static final Logger log = LoggerFactory.getLogger(ABTestService.class);
    private static final int VARIANT_A_BUCKET = 0;
    private static final int VARIANT_B_BUCKET = 1;

    private final ABTestConfig config;

    public ABTestService(ABTestConfig config) {
        this.config = config;
    }

    /**
     * Assigns a user to a variant using the layer name configured in {@link ABTestConfig}.
     * Shorthand for {@link #getVariantForUser(String, String)}.
     */
    public String getVariantForUser(String userId) {
        return getAssignmentForUser(userId).variant();
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
        return getAssignmentForUser(userId, layerName).variant();
    }

    /**
     * Returns the full deterministic assignment using the configured layer name.
     */
    public Assignment getAssignmentForUser(String userId) {
        return getAssignmentForUser(userId, config.getLayerName());
    }

    /**
     * Returns the full deterministic assignment for the provided user and layer.
     * This is useful when downstream callers need the chosen variant and the bucket metadata
     * without recomputing the hash more than once.
     */
    public Assignment getAssignmentForUser(String userId, String layerName) {
        String effectiveLayerName = normalizeLayerName(layerName);
        String defaultVariant = config.getDefaultVariant();
        if (!config.isEnabled() || userId == null || userId.isBlank()) {
            return Assignment.control(defaultVariant, effectiveLayerName);
        }

        int bucket = resolveBucket(userId, effectiveLayerName);
        if (bucket == VARIANT_A_BUCKET) {
            String variant = config.getBucketAVariant();
            log.debug("user {} in layer '{}' bucketed into A ({})", userId, effectiveLayerName, variant);
            return new Assignment(variant, bucket, effectiveLayerName, true);
        }
        if (bucket == VARIANT_B_BUCKET) {
            String variant = config.getBucketBVariant();
            log.debug("user {} in layer '{}' bucketed into B ({})", userId, effectiveLayerName, variant);
            return new Assignment(variant, bucket, effectiveLayerName, true);
        }
        return new Assignment(defaultVariant, bucket, effectiveLayerName, false);
    }

    private int resolveBucket(String userId, String layerName) {
        int split = config.getTrafficSplitNumber();
        if (split <= 0) {
            return -1;
        }
        String key = userId + ":" + layerName;
        return (key.hashCode() & Integer.MAX_VALUE) % split;
    }

    private String normalizeLayerName(String layerName) {
        if (layerName == null || layerName.isBlank()) {
            return config.getLayerName();
        }
        return layerName;
    }

    public record Assignment(String variant, int bucket, String layerName, boolean inExperiment) {

        public static Assignment control(String variant, String layerName) {
            return new Assignment(variant, -1, layerName, false);
        }
    }
}
