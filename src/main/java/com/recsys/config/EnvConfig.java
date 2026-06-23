package com.recsys.config;

/**
 * Reads numeric configuration from environment variables with a fallback default.
 *
 * Each reader returns {@code defaultValue} when the variable is unset, blank, or
 * not parseable as the requested numeric type — never throwing on bad input.
 * Centralises a parse-with-default idiom that was otherwise re-declared privately
 * across many serving/config classes.
 */
public final class EnvConfig {

    private EnvConfig() {}

    public static int readInt(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static long readLong(String name, long defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static double readDouble(String name, double defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
