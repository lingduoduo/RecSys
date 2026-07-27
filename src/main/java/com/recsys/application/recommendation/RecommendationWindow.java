package com.recsys.application.recommendation;

import com.recsys.api.response.RecommendResponse;

import java.util.Objects;

/**
 * Ranked source window plus an explicit signal that its configured acquisition budget was reached.
 */
public record RecommendationWindow(
        RecommendResponse response,
        boolean sourceTruncated
) {
    public RecommendationWindow {
        Objects.requireNonNull(response, "response");
    }
}
