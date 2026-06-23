package com.recsys.application.experiment;
import com.recsys.application.model.ModelArtifactLocator;

public final class ModelVariants {

    public static final String DEFAULT = ModelArtifactLocator.DEFAULT_VARIANT;

    private ModelVariants() {
    }

    public static String normalizeOrDefault(String variant) {
        return variant == null || variant.isBlank() ? DEFAULT : variant.trim();
    }

    public static String require(String variant) {
        if (variant == null || variant.isBlank()) {
            throw new IllegalArgumentException("variant must not be blank");
        }
        return variant.trim();
    }

    public static String trimOrEmpty(String variant) {
        return variant == null ? "" : variant.trim();
    }
}
