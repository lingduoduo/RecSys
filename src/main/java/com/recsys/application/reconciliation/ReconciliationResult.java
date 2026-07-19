package com.recsys.application.reconciliation;

/**
 * Outcome tallies for one reconciliation pass.
 *
 * <p>{@code republished} counts delivery re-attempts issued during this pass, while
 * {@code repaired} counts events whose Redis lineage was confirmed present on a later pass after an
 * earlier republish.
 */
public record ReconciliationResult(int scanned, int missing, int republished, int repaired, int failed) {
    public ReconciliationResult {
        if (scanned < 0 || missing < 0 || republished < 0 || repaired < 0 || failed < 0) {
            throw new IllegalArgumentException("counts cannot be negative");
        }
    }
}
