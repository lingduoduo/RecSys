package com.recsys.application.pagination;

/**
 * Seek (keyset) cursor anchored on the last item of the previous page rather than an absolute
 * offset. The anchor is the item's {@code (score, itemId)} in the ranker's sort order
 * (score desc, itemId asc), so removing or excluding items from the ranked list between requests
 * cannot silently skip or duplicate results the way an offset cursor would.
 * Recommendation wire encoding belongs exclusively to {@link RecommendationCursorCodec}.
 */
public record RankedListCursor(double score, String itemId) {

    /** Sentinel meaning "begin at the head of the ranked list" (no anchor yet). */
    public static final RankedListCursor START = new RankedListCursor(Double.NaN, null);

    public RankedListCursor {
        if (itemId == null) {
            if (!Double.isNaN(score)) {
                throw new IllegalArgumentException("only START may have a null itemId");
            }
        } else {
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException("score must be finite");
            }
            if (itemId.isBlank()) {
                throw new IllegalArgumentException("itemId must not be blank");
            }
            if (itemId.codePointCount(0, itemId.length()) > 512) {
                throw new IllegalArgumentException("itemId is too long");
            }
        }
    }

    /** True when this cursor has no anchor and paging should start from the first item. */
    public boolean isStart() {
        return itemId == null;
    }
}
