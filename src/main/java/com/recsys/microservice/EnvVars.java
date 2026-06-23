package com.recsys.microservice;

public final class EnvVars {
    private EnvVars() {}

    @FunctionalInterface
    public interface EnvReader {
        String get(String name);
    }

    public static int readInt(EnvReader env, String name, int def) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid integer: " + raw);
        }
    }

    public static long readLong(EnvReader env, String name, long def) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid long: " + raw);
        }
    }

    public static double readDouble(EnvReader env, String name, double def) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + name + " is not a valid decimal: " + raw);
        }
    }

    public static int readInt(String name, int def) {
        return readInt(System::getenv, name, def);
    }

    public static long readLong(String name, long def) {
        return readLong(System::getenv, name, def);
    }
}
