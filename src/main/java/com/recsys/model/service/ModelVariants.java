package com.recsys.model.service;

final class ModelVariants {

    static final String DEFAULT = ModelArtifactLocator.DEFAULT_VARIANT;

    private ModelVariants() {
    }

    static String normalizeOrDefault(String variant) {
        return variant == null || variant.isBlank() ? DEFAULT : variant.trim();
    }

    static String require(String variant) {
        if (variant == null || variant.isBlank()) {
            throw new IllegalArgumentException("variant must not be blank");
        }
        return variant.trim();
    }

    static String trimOrEmpty(String variant) {
        return variant == null ? "" : variant.trim();
    }
}
