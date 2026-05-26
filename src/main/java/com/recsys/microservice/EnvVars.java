package com.recsys.microservice;

final class EnvVars {
    private EnvVars() {}

    @FunctionalInterface
    interface EnvReader {
        String get(String name);
    }

    static int readInt(EnvReader env, String name, int def) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid integer: " + raw);
        }
    }

    static long readLong(EnvReader env, String name, long def) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid long: " + raw);
        }
    }

    static double readDouble(EnvReader env, String name, double def) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid decimal: " + raw);
        }
    }

    static int readInt(String name, int def) {
        return readInt(System::getenv, name, def);
    }

    static long readLong(String name, long def) {
        return readLong(System::getenv, name, def);
    }
}
