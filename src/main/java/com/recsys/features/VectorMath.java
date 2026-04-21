package com.recsys.features;

public class VectorMath {

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

    // Use when the query vector is compared against many candidates: compute normSq(query) once,
    // then call this overload to skip recomputing it on each candidate.
    public static double cosine(float[] a, double normSqA, float[] b) {
        if (a == null || b == null || a.length != b.length || normSqA == 0.0) return Double.NEGATIVE_INFINITY;

        double dot = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i], y = b[i];
            dot += x * y;
            nb += y * y;
        }
        if (nb == 0.0) return Double.NEGATIVE_INFINITY;
        return dot / Math.sqrt(normSqA * nb);
    }

    public static double normSq(float[] v) {
        double n = 0.0;
        for (float x : v) n += (double) x * x;
        return n;
    }

    public static float[] parseVector(String s) {
        String[] parts = s.trim().split("\\s+");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) vec[i] = Float.parseFloat(parts[i]);
        return vec;
    }
}
