package com.recsys.model.service;

import com.recsys.config.ABTestConfig;
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
        return getAssignmentForUser(userId).variant();
    }

    /**
     * Deterministically assigns a user to a variant for the given layer.
     * The hash key is {@code userId + ":" + layerName}, so two layers with different names
     * produce independent slot assignments for the same user.
     *
     * Slot [0, aPercent*100) → variant A, [aPercent*100, (aPercent+bPercent)*100) → variant B,
     * remainder → default (control).
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
     * This is useful when downstream callers need the chosen variant and the slot metadata
     * without recomputing the hash more than once.
     */
    public Assignment getAssignmentForUser(String userId, String layerName) {
        Snapshot s = snapshot();
        String layer = (layerName == null || layerName.isBlank()) ? s.layerName() : layerName;
        if (!s.enabled() || userId == null || userId.isBlank()) {
            return Assignment.control(s.defaultVariant(), layer);
        }
        int slot = StableBucketer.slot(userId, layer);
        int aEnd = s.bucketAPercent() * (StableBucketer.KEYSPACE / 100);
        int bEnd = aEnd + s.bucketBPercent() * (StableBucketer.KEYSPACE / 100);
        if (slot < aEnd) {
            log.debug("user {} layer '{}' slot {} -> A ({})", userId, layer, slot, s.bucketAVariant());
            return new Assignment(s.bucketAVariant(), slot, layer, true);
        }
        if (slot < bEnd) {
            log.debug("user {} layer '{}' slot {} -> B ({})", userId, layer, slot, s.bucketBVariant());
            return new Assignment(s.bucketBVariant(), slot, layer, true);
        }
        return new Assignment(s.defaultVariant(), slot, layer, false);
    }

    /** Default/control variant from config — used by VariantRuntimeResolver for fallback. */
    public String defaultVariant() {
        return config.getDefaultVariant();
    }

    private Snapshot snapshot() {
        return new Snapshot(config.isEnabled(), config.getBucketAPercent(), config.getBucketBPercent(),
                config.getBucketAVariant(), config.getBucketBVariant(), config.getDefaultVariant(),
                config.getLayerName());
    }

    private record Snapshot(boolean enabled, int bucketAPercent, int bucketBPercent,
                            String bucketAVariant, String bucketBVariant, String defaultVariant,
                            String layerName) {}

    public record Assignment(String variant, int slot, String layerName, boolean inExperiment) {
        public static Assignment control(String variant, String layerName) {
            return new Assignment(variant, -1, layerName, false);
        }
    }
}
