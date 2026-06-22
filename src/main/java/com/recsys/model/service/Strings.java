package com.recsys.model.service;

/**
 * Small string helpers shared across the model-serving services.
 */
final class Strings {

    private Strings() {}

    /** Returns the trimmed value, or {@code defaultValue} when it is null or blank. */
    static String orDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
