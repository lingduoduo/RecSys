package com.recsys.application.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Result of a multichannel recall: the ranked candidates plus the set of
 * non-primary channel names that returned empty due to rejection/timeout/error.
 * The bounded outcome distinguishes a naturally empty healthy recall from recall
 * that is empty because every attempted channel failed.
 */
public record RecallResult(List<MovieCandidate> candidates,
                           Set<String> degradedChannels,
                           DegradationOutcome outcome) {
    public RecallResult {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(degradedChannels, "degradedChannels");
        Objects.requireNonNull(outcome, "outcome");
        candidates = List.copyOf(candidates);
        degradedChannels = Set.copyOf(degradedChannels);
        if (outcome == DegradationOutcome.HEALTHY && !degradedChannels.isEmpty()) {
            throw new IllegalArgumentException("HEALTHY recall cannot have degraded channels");
        }
        if ((outcome == DegradationOutcome.PARTIAL || outcome == DegradationOutcome.ALL_CHANNELS)
                && degradedChannels.isEmpty()) {
            throw new IllegalArgumentException(outcome + " recall requires degraded channels");
        }
    }

    /**
     * Migration constructor for callers that do not yet distinguish partial from
     * wholly degraded recall. New recall producers must use the explicit outcome.
     */
    public RecallResult(List<MovieCandidate> candidates, Set<String> degradedChannels) {
        this(candidates, degradedChannels,
                degradedChannels == null || degradedChannels.isEmpty()
                        ? DegradationOutcome.HEALTHY
                        : DegradationOutcome.PARTIAL);
    }

    public enum DegradationOutcome {
        HEALTHY("healthy"),
        PARTIAL("partial"),
        ALL_CHANNELS("all_channels"),
        FALLBACK("fallback");

        private final String wireValue;

        DegradationOutcome(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static DegradationOutcome fromWireValue(String value) {
            for (DegradationOutcome outcome : values()) {
                if (outcome.wireValue.equals(value)) return outcome;
            }
            throw new IllegalArgumentException("unknown degradation outcome: " + value);
        }
    }
}
