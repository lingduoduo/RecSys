package com.recsys.service.retrieval.coldstart;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-port quota policy: ordered warm/cold channel fractions plus the residual channel that
 * receives whatever slots remain after rounding. Generalises the legacy {@link QuotaSpec#warm}
 * / {@link QuotaSpec#cold} logic so each serving path can supply its own channel set.
 *
 * Slot rounding per request: for each non-residual channel in iteration order,
 * {@code slot = clamp(round(fraction * limit), 0, remaining)}; the residual channel gets
 * {@code max(0, remaining)}. {@link #defaultMovie()} reproduces the legacy numbers exactly.
 */
public record QuotaPolicy(
        Map<String, Double> warmFractions, String warmResidualChannel,
        Map<String, Double> coldFractions, String coldResidualChannel) {

    public QuotaPolicy {
        warmFractions = validateAndCopy(warmFractions, warmResidualChannel, "warm");
        coldFractions = validateAndCopy(coldFractions, coldResidualChannel, "cold");
    }

    private static Map<String, Double> validateAndCopy(Map<String, Double> fractions,
                                                       String residualChannel, String label) {
        Objects.requireNonNull(fractions, label + "Fractions");
        Objects.requireNonNull(residualChannel, label + "ResidualChannel");
        Map<String, Double> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : fractions.entrySet()) {
            Objects.requireNonNull(e.getKey(), label + " channel name");
            Objects.requireNonNull(e.getValue(), label + " fraction");
            if (e.getValue() < 0.0) {
                throw new IllegalArgumentException(label + " fraction must be >= 0: " + e.getKey());
            }
            copy.put(e.getKey(), e.getValue());
        }
        if (copy.containsKey(residualChannel)) {
            throw new IllegalArgumentException(
                    label + " residual channel must not appear in its fraction map: " + residualChannel);
        }
        return Collections.unmodifiableMap(copy);
    }

    public QuotaSpec warm(int limit) { return compute(warmFractions, warmResidualChannel, limit); }

    public QuotaSpec cold(int limit) { return compute(coldFractions, coldResidualChannel, limit); }

    private static QuotaSpec compute(Map<String, Double> fractions, String residualChannel, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive, got: " + limit);
        Map<String, Integer> slots = new LinkedHashMap<>();
        int remaining = limit;
        for (Map.Entry<String, Double> e : fractions.entrySet()) {
            int slot = (int) Math.round(e.getValue() * limit);
            if (slot < 0) slot = 0;
            if (slot > remaining) slot = remaining;
            slots.put(e.getKey(), slot);
            remaining -= slot;
        }
        slots.put(residualChannel, Math.max(0, remaining));
        return new QuotaSpec(slots);
    }

    /** Port-7010 quota: embedding + online-recent-history led when warm; cold-start led when cold. */
    public static QuotaPolicy defaultOnline() {
        Map<String, Double> warm = new LinkedHashMap<>();
        warm.put("embedding", 0.45);
        warm.put("online_recent_history", 0.20);
        warm.put("user_similarity", 0.15);
        warm.put("trending", 0.15);
        Map<String, Double> cold = new LinkedHashMap<>();
        cold.put("cold_start", 0.50);
        cold.put("trending", 0.20);
        cold.put("popularity", 0.20);
        return new QuotaPolicy(warm, "popularity", cold, "online_recent_history");
    }

    /** The port-6010 quota numbers, reproducing the legacy {@link QuotaSpec} statics exactly. */
    public static QuotaPolicy defaultMovie() {
        Map<String, Double> warm = new LinkedHashMap<>();
        warm.put("embedding", 0.50);
        warm.put("user_similarity", 0.20);
        warm.put("trending", 0.15);
        warm.put("genre_history", 0.10);
        Map<String, Double> cold = new LinkedHashMap<>();
        cold.put("cold_start", 0.50);
        cold.put("trending", 0.20);
        cold.put("popularity", 0.20);
        return new QuotaPolicy(warm, "popularity", cold, "genre_history");
    }

    /** Port-8080 model-serving retrieval quota: warm blends embedding + online_recent_history + trending (the ONNX ranker personalizes); cold-start led cold. */
    public static QuotaPolicy defaultModelRetrieval() {
        Map<String, Double> warm = new LinkedHashMap<>();
        warm.put("embedding", 0.45);
        warm.put("online_recent_history", 0.15);
        warm.put("user_similarity", 0.15);
        warm.put("trending", 0.10);
        Map<String, Double> cold = new LinkedHashMap<>();
        cold.put("cold_start", 0.50);
        cold.put("trending", 0.25);
        return new QuotaPolicy(warm, "popularity", cold, "popularity");
    }
}
