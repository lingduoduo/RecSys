package com.recsys.application.retrieval.multichannel;

import com.recsys.domain.item.MovieCandidate;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Result of a multichannel recall: the ranked candidates plus the set of
 * non-primary channel names that returned empty due to rejection/timeout/error.
 * An empty {@code degradedChannels} means full-quality recall.
 */
public record RecallResult(List<MovieCandidate> candidates, Set<String> degradedChannels) {
    public RecallResult {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(degradedChannels, "degradedChannels");
        candidates = List.copyOf(candidates);
        degradedChannels = Set.copyOf(degradedChannels);
    }
}
