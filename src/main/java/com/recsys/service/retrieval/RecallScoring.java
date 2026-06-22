package com.recsys.service.retrieval;

import com.recsys.domain.MovieCandidate;
import com.recsys.domain.RecommendationQuery;
import com.recsys.online.store.TrendingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Scoring/merge primitives shared by the recall channels and the merge service.
 * Centralises the rank-decay, window-blend, candidate-ordering, and user-id
 * parsing idioms that were previously copy-pasted across channels.
 */
public final class RecallScoring {

    private RecallScoring() {}

    /**
     * Canonical candidate ordering: score descending, then itemId ascending so
     * ties are broken deterministically. Used by every channel and merge stage.
     */
    public static final java.util.Comparator<MovieCandidate> BY_SCORE_DESC =
            java.util.Comparator.comparingDouble(MovieCandidate::score).reversed()
                    .thenComparing(MovieCandidate::itemId);

    /**
     * Rank-decay candidates for an already-ordered id list: {@code ids[i]} gets
     * score {@code 1/(i+1)}, tagged with {@code channel} and empty metadata.
     */
    public static List<MovieCandidate> rankScored(List<String> ids, String channel) {
        List<MovieCandidate> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            out.add(new MovieCandidate(ids.get(i), 1.0 / (i + 1.0), channel, Map.of()));
        }
        return out;
    }

    /**
     * Merge an ordered id list into {@code into} with weighted rank decay:
     * {@code into[ids[i]] += weight * 1/(i+1)}.
     */
    public static void blendRankDecay(Map<String, Double> into, List<String> ids, double weight) {
        for (int i = 0; i < ids.size(); i++) {
            into.merge(ids.get(i), weight * (1.0 / (i + 1.0)), Double::sum);
        }
    }

    /**
     * Apply {@link #blendRankDecay} across each {@code window -> weight} entry,
     * pulling {@code store.getTopKIds(window, limit)} for each window.
     */
    public static void blendWindows(Map<String, Double> into, TrendingStore store,
                                    Map<String, Double> weights, int limit) {
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            blendRankDecay(into, store.getTopKIds(entry.getKey(), limit), entry.getValue());
        }
    }

    /** Parse the numeric user id, empty when non-numeric — the common channel guard. */
    public static OptionalInt parseUserId(RecommendationQuery query) {
        try {
            return OptionalInt.of(Integer.parseInt(query.userId()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
