package com.recsys.infrastructure.vectordb;

public final class VectorMath {

    private VectorMath() {
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return Double.NEGATIVE_INFINITY;

        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i], y = b[i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0.0 || nb == 0.0) return Double.NEGATIVE_INFINITY;
        return dot / Math.sqrt(na * nb);
    }

    public static double innerProduct(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return Double.NEGATIVE_INFINITY;
        int len = a.length;
        // 4-way unrolled accumulation reduces loop overhead and lets the JIT pipeline
        // four multiply-add operations per iteration across independent accumulators.
        double s0 = 0, s1 = 0, s2 = 0, s3 = 0;
        int i = 0;
        for (; i <= len - 4; i += 4) {
            s0 += (double) a[i]     * b[i];
            s1 += (double) a[i + 1] * b[i + 1];
            s2 += (double) a[i + 2] * b[i + 2];
            s3 += (double) a[i + 3] * b[i + 3];
        }
        double dot = s0 + s1 + s2 + s3;
        for (; i < len; i++) dot += (double) a[i] * b[i];
        return dot;
    }

    public static double normSq(float[] v) {
        if (v == null) return 0.0;
        double n = 0.0;
        for (float x : v) n += (double) x * x;
        return n;
    }

    public static float[] parseVector(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("vector must not be blank");
        }
        String[] parts = s.trim().split("\\s+");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            float value = Float.parseFloat(parts[i]);
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("vector contains non-finite value: " + parts[i]);
            }
            vec[i] = value;
        }
        return vec;
    }
}
