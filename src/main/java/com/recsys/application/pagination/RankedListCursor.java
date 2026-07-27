package com.recsys.application.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Seek (keyset) cursor anchored on the last item of the previous page rather than an absolute
 * offset. The anchor is the item's {@code (score, itemId)} in the ranker's sort order
 * (score desc, itemId asc), so removing or excluding items from the ranked list between requests
 * cannot silently skip or duplicate results the way an offset cursor would.
 *
 * <p>Wire format (base64url, unpadded): {@code v2:<score>:<itemId>}. {@code itemId} is the trailing
 * field and may itself contain {@code ':'}. A blank token decodes to {@link #START} (first page).
 */
public record RankedListCursor(double score, String itemId) {

    private static final String PREFIX = "v2:";

    /** Sentinel meaning "begin at the head of the ranked list" (no anchor yet). */
    public static final RankedListCursor START = new RankedListCursor(Double.NaN, null);

    public RankedListCursor {
        if (itemId != null) {
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

    public String encode() {
        if (isStart()) {
            throw new IllegalStateException("the START cursor is not encodable");
        }
        String raw = PREFIX + Double.toString(score) + ":" + itemId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static RankedListCursor decode(String token) {
        if (token == null || token.isBlank()) {
            return START;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(PREFIX)) {
                throw new IllegalArgumentException("unsupported cursor version");
            }
            String body = decoded.substring(PREFIX.length());
            int sep = body.indexOf(':');
            if (sep < 0) {
                throw new IllegalArgumentException("missing itemId in cursor");
            }
            double score = Double.parseDouble(body.substring(0, sep));
            String itemId = body.substring(sep + 1);
            if (itemId.isBlank()) {
                throw new IllegalArgumentException("blank itemId in cursor");
            }
            return new RankedListCursor(score, itemId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid ranked-list cursor", e);
        }
    }
}
