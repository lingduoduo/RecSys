package com.recsys.application.pagination;

import com.recsys.domain.item.RankedMovie;
import com.recsys.domain.recommendation.RecommendationQuery;

import java.util.List;
import java.util.Objects;

/** Owns recommendation cursor validation, page assembly, invariants, and metrics. */
public final class RecommendationPaginationCoordinator {
    private final RecommendationCursorCodec codec;
    private final CursorPaginationService pagination;
    private final RecommendationPaginationMetrics metrics;

    public RecommendationPaginationCoordinator(
            RecommendationCursorCodec codec,
            CursorPaginationService pagination,
            RecommendationPaginationMetrics metrics
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.pagination = Objects.requireNonNull(pagination, "pagination");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Decodes and binds the cursor before callers perform recall, ranking, or hydration work.
     */
    public DecodedRequest decode(RecommendationQuery query) {
        Objects.requireNonNull(query, "query");
        try {
            DecodedCursor decoded = query.cursor() == null
                    ? new DecodedCursor(RankedListCursor.START, false, false)
                    : codec.decode(query, query.cursor());
            return new DecodedRequest(query, decoded);
        } catch (RecommendationCursorCodec.InvalidCursorException e) {
            metrics.cursorRejected(e.reason());
            throw e;
        }
    }

    /**
     * Convenience path for callers that already have ranked items. Serving adapters should call
     * {@link #decode(RecommendationQuery)} before source work and use the split overload.
     */
    public RecommendationPage page(
            RecommendationQuery query,
            List<RankedMovie> orderedItems,
            boolean sourceTruncated
    ) {
        return page(decode(query), orderedItems, sourceTruncated);
    }

    public RecommendationPage page(
            DecodedRequest request,
            List<RankedMovie> orderedItems,
            boolean sourceTruncated
    ) {
        Objects.requireNonNull(request, "request");
        RecommendationQuery query = request.query();
        DecodedCursor decoded = request.cursor();
        Page<RankedMovie> page = pagination.page(
                orderedItems,
                decoded.position(),
                query.limit(),
                RankedMovie::score,
                RankedMovie::itemId);
        String next = page.hasMore()
                ? codec.encode(query, page.nextPosition())
                : null;
        if (decoded.legacy()) {
            metrics.legacyAccepted();
        }
        if (decoded.previousKey()) {
            metrics.previousKeyVerified();
        }
        boolean budgetExhausted = sourceTruncated && !page.hasMore();
        if (budgetExhausted) {
            metrics.budgetExhausted();
        }
        metrics.pageReturned(!page.hasMore());
        return new RecommendationPage(
                page.items(), next, page.hasMore(), decoded.legacy(), budgetExhausted);
    }

    /** Opaque decoded request token passed from pre-source validation to page assembly. */
    public record DecodedRequest(RecommendationQuery query, DecodedCursor cursor) {
        public DecodedRequest {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(cursor, "cursor");
        }
    }

    /** Recommendation page plus migration and bounded-source metadata. */
    public record RecommendationPage(
            List<RankedMovie> items,
            String nextCursor,
            boolean hasMore,
            boolean legacyCursor,
            boolean budgetExhausted
    ) {
        public RecommendationPage {
            items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
            nextCursor = nextCursor == null || nextCursor.isBlank()
                    ? null
                    : nextCursor.trim();
            if (hasMore != (nextCursor != null)) {
                throw new IllegalArgumentException("hasMore and nextCursor must agree");
            }
        }
    }
}
